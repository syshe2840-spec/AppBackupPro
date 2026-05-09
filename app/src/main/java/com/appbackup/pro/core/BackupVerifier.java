package com.appbackup.pro.core;

import android.util.Log;

import com.appbackup.pro.models.BackupMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * تأیید بکاپ - چک می‌کنه که بکاپ کامل و سالمه
 * بعد از ریستور هم چک می‌کنه که همه چی درست ریستور شده
 */
public class BackupVerifier {
    private static final String TAG = "AppBackupPro_DEBUG";

    public static class VerifyResult {
        public boolean success;
        public List<String> errors;
        public List<String> warnings;
        public List<String> info;

        public VerifyResult() {
            errors = new ArrayList<>();
            warnings = new ArrayList<>();
            info = new ArrayList<>();
            success = true;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(success ? "✅ Verification passed\n\n" : "❌ Verification failed\n\n");
            
            if (!errors.isEmpty()) {
                sb.append("Errors (").append(errors.size()).append("):\n");
                for (String e : errors) sb.append("  ✗ ").append(e).append("\n");
                sb.append("\n");
            }
            if (!warnings.isEmpty()) {
                sb.append("Warnings (").append(warnings.size()).append("):\n");
                for (String w : warnings) sb.append("  ⚠ ").append(w).append("\n");
                sb.append("\n");
            }
            if (!info.isEmpty()) {
                sb.append("Info:\n");
                for (String i : info) sb.append("  ✓ ").append(i).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * تأیید کامل بودن یک بکاپ قبل از ریستور
     */
    public static VerifyResult verifyBackup(File backupDir, BackupMeta meta) {
        VerifyResult result = new VerifyResult();
        
        Log.d(TAG, "─── VERIFYING BACKUP ───");
        Log.d(TAG, "Path: " + backupDir.getAbsolutePath());

        // چک ۱: پوشه‌ی بکاپ وجود داره؟
        if (!backupDir.exists() || !backupDir.isDirectory()) {
            result.errors.add("Backup folder does not exist");
            result.success = false;
            return result;
        }
        result.info.add("Backup folder exists");

        // چک ۲: metadata.json
        File metaFile = new File(backupDir, "metadata.json");
        if (!metaFile.exists()) {
            result.errors.add("metadata.json missing");
            result.success = false;
        } else {
            result.info.add("metadata.json present");
        }

        // چک ۳: APK
        if (meta.hasApk()) {
            File baseApk = new File(backupDir, "base.apk");
            if (!baseApk.exists() || baseApk.length() == 0) {
                result.errors.add("base.apk missing or empty");
                result.success = false;
            } else {
                result.info.add("base.apk: " + formatSize(baseApk.length()));
            }
        }

        // چک ۴: Splits
        if (meta.hasSplitApks()) {
            File splitsDir = new File(backupDir, "splits");
            if (!splitsDir.exists() || splitsDir.listFiles() == null 
                || splitsDir.listFiles().length == 0) {
                result.warnings.add("Splits directory empty");
            } else {
                result.info.add("Splits: " + splitsDir.listFiles().length + " files");
            }
        }

        // چک ۵: Internal data
        if (meta.hasInternalData()) {
            File dataDir = new File(backupDir, "data");
            if (!dataDir.exists() || dataDir.listFiles() == null 
                || dataDir.listFiles().length == 0) {
                result.errors.add("Internal data directory empty");
                result.success = false;
            } else {
                result.info.add("Internal data: " + dataDir.listFiles().length + " items");
            }
        }

        // چک ۶: DE data
        if (meta.hasDeviceProtectedData()) {
            File deDir = new File(backupDir, "data_de");
            if (!deDir.exists()) {
                result.warnings.add("DE data directory missing");
            } else {
                result.info.add("DE data present");
            }
        }

        // چک ۷: External data
        if (meta.hasExternalData()) {
            File extDir = new File(backupDir, "ext_data");
            if (!extDir.exists()) {
                result.warnings.add("External data missing");
            } else {
                result.info.add("External data present");
            }
        }

        // چک ۸: OBB
        if (meta.hasObb()) {
            File obbDir = new File(backupDir, "obb");
            if (!obbDir.exists()) {
                result.warnings.add("OBB directory missing");
            } else {
                result.info.add("OBB present");
            }
        }

        // چک ۹: KeyStore
        if (meta.hasKeystore()) {
            File ksDir = new File(backupDir, "keystore");
            if (!ksDir.exists() || ksDir.listFiles() == null 
                || ksDir.listFiles().length == 0) {
                result.warnings.add("KeyStore directory empty - app may need re-login");
            } else {
                result.info.add("KeyStore: " + ksDir.listFiles().length + " files");
            }
        }

        Log.d(TAG, "Verify result: " + (result.success ? "PASS" : "FAIL"));
        return result;
    }

    /**
     * تأیید موفق بودن ریستور
     */
    public static VerifyResult verifyRestore(String packageName, BackupMeta meta) {
        VerifyResult result = new VerifyResult();
        
        Log.d(TAG, "─── VERIFYING RESTORE ───");
        Log.d(TAG, "Package: " + packageName);

        String dataPath = "/data/data/" + packageName;

        // چک ۱: پوشه‌ی data وجود داره
        if (!RootShell.dirExists(dataPath)) {
            result.errors.add("App data directory missing");
            result.success = false;
            return result;
        }
        result.info.add("App data directory exists");

        // چک ۲: تعداد فایل‌های ریستور شده
        RootShell.Result lsR = RootShell.run("ls -A " + dataPath + " | wc -l");
        if (lsR.success) {
            try {
                int count = Integer.parseInt(lsR.stdout.trim());
                if (count == 0) {
                    result.errors.add("App data is empty after restore");
                    result.success = false;
                } else {
                    result.info.add("Files in data: " + count);
                }
            } catch (NumberFormatException e) {
                result.warnings.add("Could not count files");
            }
        }

        // چک ۳: UID درست
        int currentUid = RootShell.getAppUid(packageName);
        if (currentUid <= 0) {
            result.errors.add("Cannot read app UID");
            result.success = false;
        } else {
            result.info.add("Current UID: " + currentUid);
            if (currentUid != meta.getUid()) {
                result.warnings.add("UID changed: was " + meta.getUid() + ", now " + currentUid);
            }
        }

        // چک ۴: SELinux context
        String context = RootShell.getSelinuxContext(dataPath);
        if (!context.contains("app_data_file")) {
            result.warnings.add("SELinux context may be wrong: " + context);
        } else {
            result.info.add("SELinux context: OK");
        }

        // چک ۵: Ownership
        RootShell.Result statR = RootShell.run("stat -c '%u:%g' " + dataPath);
        if (statR.success) {
            String owner = statR.stdout.trim();
            String expected = currentUid + ":" + currentUid;
            if (!owner.equals(expected)) {
                result.warnings.add("Ownership: expected " + expected + ", got " + owner);
            } else {
                result.info.add("Ownership: " + owner);
            }
        }

        Log.d(TAG, "Restore verify: " + (result.success ? "PASS" : "FAIL"));
        return result;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
