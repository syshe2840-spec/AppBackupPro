package com.appbackup.pro.core;

import android.util.Log;

import com.appbackup.pro.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * چک کردن یکپارچگی بکاپ با SHA256
 * موقع بکاپ hash می‌گیریم، موقع ریستور چک می‌کنیم
 */
public class IntegrityChecker {
    private static final String TAG = "AppBackupPro_DEBUG";
    private static final String CHECKSUMS_FILE = "checksums.sha256";

    /**
     * ساخت hash برای همه‌ی فایل‌های مهم بکاپ
     */
    public static int generateChecksums(File backupDir) {
        try {
            Log.d(TAG, "─── GENERATING CHECKSUMS ───");
            
            StringBuilder sb = new StringBuilder();
            int count = 0;
            
            // hash برای فایل‌های مهم در root بکاپ
            String[] importantFiles = {"base.apk", "metadata.json"};
            for (String name : importantFiles) {
                File f = new File(backupDir, name);
                if (f.exists() && f.isFile()) {
                    String hash = sha256(f);
                    if (hash != null) {
                        sb.append(hash).append("  ").append(name).append("\n");
                        count++;
                    }
                }
            }
            
            // hash برای splits
            File splitsDir = new File(backupDir, "splits");
            if (splitsDir.exists() && splitsDir.isDirectory()) {
                File[] splits = splitsDir.listFiles();
                if (splits != null) {
                    for (File split : splits) {
                        String hash = sha256(split);
                        if (hash != null) {
                            sb.append(hash).append("  splits/").append(split.getName()).append("\n");
                            count++;
                        }
                    }
                }
            }
            
            // برای پوشه‌های data، فقط یه hash از کل پوشه می‌گیریم (سریع‌تر)
            String[] dataDirs = {"data", "data_de", "ext_data", "obb", "native_libs", "keystore"};
            for (String dirName : dataDirs) {
                File dir = new File(backupDir, dirName);
                if (dir.exists() && dir.isDirectory()) {
                    String hash = hashDirectory(dir);
                    if (hash != null) {
                        sb.append(hash).append("  ").append(dirName).append("/\n");
                        count++;
                    }
                }
            }
            
            // ذخیره
            File checksumsFile = new File(backupDir, CHECKSUMS_FILE);
            FileUtils.writeString(checksumsFile, sb.toString());
            
            Log.d(TAG, "✓ Generated " + count + " checksums");
            return count;
            
        } catch (Exception e) {
            Log.e(TAG, "Checksum generation error", e);
            return 0;
        }
    }

    /**
     * چک کردن همه‌ی hashها قبل از ریستور
     */
    public static boolean verifyChecksums(File backupDir) {
        try {
            File checksumsFile = new File(backupDir, CHECKSUMS_FILE);
            if (!checksumsFile.exists()) {
                Log.w(TAG, "No checksums file - skipping verification");
                return true; // برای backward compatibility
            }
            
            Log.d(TAG, "─── VERIFYING CHECKSUMS ───");
            
            String content = FileUtils.readString(checksumsFile);
            String[] lines = content.split("\n");
            
            int verified = 0;
            int failed = 0;
            
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                // فرمت: <hash>  <path>
                int spaceIdx = line.indexOf("  ");
                if (spaceIdx < 0) continue;
                
                String expectedHash = line.substring(0, spaceIdx).trim();
                String relativePath = line.substring(spaceIdx + 2).trim();
                
                File target = new File(backupDir, relativePath.endsWith("/") 
                    ? relativePath.substring(0, relativePath.length() - 1) 
                    : relativePath);
                
                if (!target.exists()) {
                    Log.w(TAG, "  ✗ Missing: " + relativePath);
                    failed++;
                    continue;
                }
                
                String actualHash;
                if (target.isDirectory()) {
                    actualHash = hashDirectory(target);
                } else {
                    actualHash = sha256(target);
                }
                
                if (actualHash != null && actualHash.equals(expectedHash)) {
                    verified++;
                } else {
                    Log.w(TAG, "  ✗ Hash mismatch: " + relativePath);
                    Log.w(TAG, "    Expected: " + expectedHash);
                    Log.w(TAG, "    Actual:   " + actualHash);
                    failed++;
                }
            }
            
            Log.d(TAG, "Verification: " + verified + " passed, " + failed + " failed");
            return failed == 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Verification error", e);
            return false;
        }
    }

    /**
     * SHA256 یه فایل
     */
    private static String sha256(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            Log.e(TAG, "SHA256 error for " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * hash یه پوشه (با ترکیب همه‌ی فایل‌ها)
     */
    private static String hashDirectory(File dir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            Map<String, String> fileHashes = new HashMap<>();
            collectFileHashes(dir, dir.getAbsolutePath(), fileHashes);
            
            // مرتب‌سازی برای اطمینان از reproducibility
            String[] paths = fileHashes.keySet().toArray(new String[0]);
            java.util.Arrays.sort(paths);
            
            for (String path : paths) {
                md.update(path.getBytes("UTF-8"));
                md.update(fileHashes.get(path).getBytes("UTF-8"));
            }
            
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            Log.e(TAG, "Directory hash error: " + e.getMessage());
            return null;
        }
    }

    private static void collectFileHashes(File dir, String basePath, Map<String, String> map) {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            if (f.isDirectory()) {
                collectFileHashes(f, basePath, map);
            } else {
                String relativePath = f.getAbsolutePath().substring(basePath.length());
                String hash = sha256(f);
                if (hash != null) {
                    map.put(relativePath, hash);
                }
            }
        }
    }
}
