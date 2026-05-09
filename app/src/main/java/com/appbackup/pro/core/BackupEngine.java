package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.AppInfo;
import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BackupEngine {
    private static final String TAG = "AppBackupPro_DEBUG";

    private final Context context;
    private ProgressCallback progressCallback;

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public BackupEngine(Context context) {
        this.context = context;
    }

    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    private void updateProgress(String message, int percent) {
        Log.d(TAG, "═══ PROGRESS [" + percent + "%] " + message);
        if (progressCallback != null) {
            progressCallback.onProgress(message, percent);
        }
    }

    private void logResult(String operation, RootShell.Result r) {
        Log.d(TAG, "▶▶▶ " + operation);
        Log.d(TAG, "    exitCode = " + r.exitCode);
        Log.d(TAG, "    success  = " + r.success);
        Log.d(TAG, "    stdout   = [" + r.stdout + "]");
        Log.d(TAG, "    stderr   = [" + r.stderr + "]");
    }

    public BackupResult backup(AppInfo appInfo, String backupName) {
        long startTime = System.currentTimeMillis();
        String packageName = appInfo.getPackageName();
        File backupDir = null;

        Log.d(TAG, "");
        Log.d(TAG, "╔════════════════════════════════════════");
        Log.d(TAG, "║ BACKUP START: " + packageName);
        Log.d(TAG, "╚════════════════════════════════════════");

        try {
            // مرحله ۱: روت
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                return BackupResult.failure("Root access denied");
            }
            Log.d(TAG, "✓ Root OK");

            // مرحله ۲: بررسی اپ
            updateProgress("Checking app...", 5);
            String dataPath = "/data/data/" + packageName;
            if (!RootShell.dirExists(dataPath)) {
                return BackupResult.failure("App data not found: " + packageName);
            }
            Log.d(TAG, "✓ Data path exists");

            // مرحله ۳: ساخت پوشه‌ی بکاپ
            updateProgress("Creating backup folder...", 8);
            String folderName = FileUtils.generateBackupFolderName(packageName, backupName);
            backupDir = new File(FileUtils.getBackupRootDir(context), folderName);
            if (!FileUtils.ensureDir(backupDir)) {
                return BackupResult.failure("Cannot create backup folder");
            }
            Log.d(TAG, "Backup dir: " + backupDir.getAbsolutePath());

            // مرحله ۴: اطلاعات اپ
            updateProgress("Getting app info...", 10);
            int uid = RootShell.getAppUid(packageName);
            String selinuxContext = RootShell.getSelinuxContext(dataPath);
            Log.d(TAG, "UID: " + uid + " | SELinux: " + selinuxContext);

            BackupMeta meta = new BackupMeta();
            meta.setBackupId(UUID.randomUUID().toString());
            meta.setBackupName(backupName != null && !backupName.isEmpty() ? backupName : appInfo.getAppName());
            meta.setPackageName(packageName);
            meta.setAppName(appInfo.getAppName());
            meta.setVersionName(appInfo.getVersionName());
            meta.setVersionCode(appInfo.getVersionCode());
            meta.setCreatedAt(System.currentTimeMillis());
            meta.setUid(uid);
            meta.setSelinuxContext(selinuxContext);

            // مرحله ۵: توقف اپ
            updateProgress("Stopping app...", 12);
            RootShell.run("am force-stop " + packageName);
            Thread.sleep(800);

            // مرحله ۶: بکاپ APK
            updateProgress("Backing up APK...", 18);
            if (appInfo.getApkPath() != null && !appInfo.getApkPath().isEmpty()) {
                File apkDest = new File(backupDir, "base.apk");
                String apkCmd = "cp " + RootShell.escapePath(appInfo.getApkPath())
                        + " " + RootShell.escapePath(apkDest.getAbsolutePath());
                RootShell.Result apkR = RootShell.run(apkCmd);
                logResult("APK copy", apkR);
                if (apkR.success && apkDest.exists() && apkDest.length() > 0) {
                    meta.setHasApk(true);
                    Log.d(TAG, "✓ APK backed up: " + apkDest.length() + " bytes");
                }
            }

            // مرحله ۷: Split APKs
            updateProgress("Backing up split APKs...", 25);
            if (appInfo.hasSplits()) {
                File splitsDir = new File(backupDir, "splits");
                FileUtils.ensureDir(splitsDir);
                boolean allOk = true;
                for (String splitPath : appInfo.getSplitApkPaths()) {
                    File splitFile = new File(splitPath);
                    File dest = new File(splitsDir, splitFile.getName());
                    String splitCmd = "cp " + RootShell.escapePath(splitPath)
                            + " " + RootShell.escapePath(dest.getAbsolutePath());
                    RootShell.Result splitR = RootShell.run(splitCmd);
                    logResult("Split: " + splitFile.getName(), splitR);
                    if (!splitR.success || !dest.exists()) allOk = false;
                }
                meta.setHasSplitApks(allOk);
            }

            // مرحله ۸: Internal Data ⭐
            updateProgress("Backing up internal data...", 35);
            File dataDir = new File(backupDir, "data");
            FileUtils.ensureDir(dataDir);
            
            Log.d(TAG, "─── INTERNAL DATA BACKUP ───");
            
            String cpCmd = "cp -rfL " + dataPath + "/. " + RootShell.escapePath(dataDir.getAbsolutePath() + "/");
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("Internal cp -rfL", r);
            
            File[] backedUp = dataDir.listFiles();
            int fileCount = backedUp != null ? backedUp.length : 0;
            
            if (fileCount > 0) {
                meta.setHasInternalData(true);
                Log.d(TAG, "✓ Internal data: " + fileCount + " items");
            } else {
                throw new Exception("Internal data backup failed - no files copied");
            }

            // مرحله ۹: DE Data
            updateProgress("Backing up device-protected data...", 50);
            String dePath = "/data/user_de/0/" + packageName;
            if (RootShell.dirExists(dePath)) {
                File deDir = new File(backupDir, "data_de");
                FileUtils.ensureDir(deDir);
                String deCmd = "cp -rfL " + dePath + "/. " + RootShell.escapePath(deDir.getAbsolutePath() + "/");
                RootShell.Result deR = RootShell.run(deCmd);
                logResult("DE cp", deR);
                File[] deFiles = deDir.listFiles();
                if (deFiles != null && deFiles.length > 0) {
                    meta.setHasDeviceProtectedData(true);
                    Log.d(TAG, "✓ DE data: " + deFiles.length + " items");
                }
            }

            // مرحله ۱۰: External Data
            updateProgress("Backing up external data...", 62);
            String extPath = "/sdcard/Android/data/" + packageName;
            if (RootShell.dirExists(extPath)) {
                File extDir = new File(backupDir, "ext_data");
                FileUtils.ensureDir(extDir);
                String extCmd = "cp -rfL " + extPath + "/. " + RootShell.escapePath(extDir.getAbsolutePath() + "/");
                RootShell.Result extR = RootShell.run(extCmd);
                logResult("Ext cp", extR);
                File[] extFiles = extDir.listFiles();
                if (extFiles != null && extFiles.length > 0) {
                    meta.setHasExternalData(true);
                    Log.d(TAG, "✓ External: " + extFiles.length + " items");
                }
            }

            // مرحله ۱۱: OBB
            updateProgress("Backing up OBB files...", 74);
            String obbPath = "/sdcard/Android/obb/" + packageName;
            if (RootShell.dirExists(obbPath)) {
                File obbDir = new File(backupDir, "obb");
                FileUtils.ensureDir(obbDir);
                String obbCmd = "cp -rfL " + obbPath + "/. " + RootShell.escapePath(obbDir.getAbsolutePath() + "/");
                RootShell.Result obbR = RootShell.run(obbCmd);
                logResult("OBB cp", obbR);
                File[] obbFiles = obbDir.listFiles();
                if (obbFiles != null && obbFiles.length > 0) {
                    meta.setHasObb(true);
                    Log.d(TAG, "✓ OBB: " + obbFiles.length + " items");
                }
            }

            // ⭐ مرحله ۱۲: KeyStore (مهم برای اپ‌های با encryption!)
            updateProgress("Backing up keystore keys...", 84);
            backupKeystore(backupDir, meta, uid);

            // مرحله ۱۳: محاسبه‌ی سایز
            updateProgress("Calculating size...", 92);
            long totalSize = FileUtils.getFolderSize(backupDir);
            meta.setTotalSize(totalSize);
            Log.d(TAG, "Total: " + FileUtils.formatSize(totalSize));

            // مرحله ۱۴: permissions
            updateProgress("Fixing permissions...", 95);
            RootShell.run("chmod -R 755 " + RootShell.escapePath(backupDir.getAbsolutePath()));

            // مرحله ۱۵: metadata
            updateProgress("Writing metadata...", 98);
            File metaFile = new File(backupDir, "metadata.json");
            FileUtils.writeString(metaFile, meta.toJson().toString(2));

            updateProgress("Backup complete!", 100);
            
            Log.d(TAG, "");
            Log.d(TAG, "╔════════════════════════════════════════");
            Log.d(TAG, "║ BACKUP COMPLETE ✓");
            Log.d(TAG, "║ Duration: " + (System.currentTimeMillis() - startTime) + "ms");
            Log.d(TAG, "║ Size: " + FileUtils.formatSize(totalSize));
            Log.d(TAG, "╚════════════════════════════════════════");

            BackupResult result = BackupResult.success(
                    "Backup completed (" + FileUtils.formatSize(totalSize) + ")",
                    backupDir.getAbsolutePath()
            );
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "BACKUP FAILED", e);
            if (backupDir != null && backupDir.exists()) {
                FileUtils.deleteRecursive(backupDir);
            }
            return BackupResult.failure("Backup failed: " + e.getMessage(), e.toString());
        }
    }

    /**
     * ⭐ بکاپ KeyStore برای حل مشکل AEADBadTagException
     */
    private void backupKeystore(File backupDir, BackupMeta meta, int uid) {
        try {
            File keystoreDir = new File(backupDir, "keystore");
            FileUtils.ensureDir(keystoreDir);
            
            Log.d(TAG, "");
            Log.d(TAG, "─── KEYSTORE BACKUP ───");
            Log.d(TAG, "App UID: " + uid);
            
            List<String> foundFiles = new ArrayList<>();
            
            // مسیرهای ممکن KeyStore در نسخه‌های مختلف اندروید
            String[] keystorePaths = {
                "/data/misc/keystore/user_0",
                "/data/misc/keystore"
            };
            
            for (String ksPath : keystorePaths) {
                if (!RootShell.dirExists(ksPath)) continue;
                
                Log.d(TAG, "Searching in: " + ksPath);
                
                // فایل‌های keystore با UID شروع میشن: مثلاً 10157_USRPKEY_xxx
                String findCmd = "find " + ksPath + " -maxdepth 2 -name '" + uid + "_*' 2>/dev/null";
                RootShell.Result findR = RootShell.run(findCmd);
                
                if (findR.success && !findR.stdout.trim().isEmpty()) {
                    String[] files = findR.stdout.trim().split("\n");
                    for (String filePath : files) {
                        filePath = filePath.trim();
                        if (filePath.isEmpty()) continue;
                        
                        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
                        File destFile = new File(keystoreDir, fileName);
                        
                        String cpCmd = "cp -f " + RootShell.escapePath(filePath)
                                + " " + RootShell.escapePath(destFile.getAbsolutePath());
                        RootShell.Result cpR = RootShell.run(cpCmd);
                        
                        if (cpR.success && destFile.exists() && destFile.length() > 0) {
                            foundFiles.add(fileName);
                            Log.d(TAG, "  ✓ " + fileName + " (" + destFile.length() + " bytes)");
                        } else {
                            Log.w(TAG, "  ✗ Failed: " + fileName);
                        }
                    }
                }
            }
            
            // فایل‌های keystore مرکزی (اندروید قدیمی)
            String[] sqliteFiles = {
                "/data/misc/keystore/persistent.sqlite",
                "/data/misc/keystore/.masterkey"
            };
            for (String sqlPath : sqliteFiles) {
                if (RootShell.exists(sqlPath)) {
                    String name = "_shared_" + sqlPath.substring(sqlPath.lastIndexOf('/') + 1);
                    File dest = new File(keystoreDir, name);
                    RootShell.Result r = RootShell.run("cp -f " + sqlPath + " " 
                        + RootShell.escapePath(dest.getAbsolutePath()));
                    if (r.success && dest.exists()) {
                        foundFiles.add(name);
                        Log.d(TAG, "  ✓ " + name);
                    }
                }
            }
            
            if (!foundFiles.isEmpty()) {
                meta.setHasKeystore(true);
                meta.setKeystoreFiles(foundFiles.toArray(new String[0]));
                Log.d(TAG, "✓ KeyStore: " + foundFiles.size() + " files");
            } else {
                Log.d(TAG, "No KeyStore files for UID " + uid);
            }
        } catch (Exception e) {
            Log.w(TAG, "KeyStore backup error (non-fatal): " + e.getMessage());
        }
    }
}
