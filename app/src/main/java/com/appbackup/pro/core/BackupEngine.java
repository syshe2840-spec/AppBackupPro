package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.AppInfo;
import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;
import java.util.UUID;

/**
 * موتور اصلی بکاپ - قلب اپ
 * بکاپ کامل: APK + Splits + Internal + DE + External + OBB + Metadata
 * از tar استفاده می‌کنیم تا socket files و SELinux issues رو دور بزنیم
 */
public class BackupEngine {
    private static final String TAG = "BackupEngine";

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
        Log.d(TAG, "[" + percent + "%] " + message);
        if (progressCallback != null) {
            progressCallback.onProgress(message, percent);
        }
    }

    /**
     * گرفتن بکاپ کامل از یک اپ
     */
    public BackupResult backup(AppInfo appInfo, String backupName) {
        long startTime = System.currentTimeMillis();
        String packageName = appInfo.getPackageName();
        File backupDir = null;

        try {
            // مرحله ۱: بررسی روت
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                return BackupResult.failure("Root access denied");
            }

            // مرحله ۲: بررسی نصب بودن اپ
            updateProgress("Checking app...", 5);
            String dataPath = "/data/data/" + packageName;
            if (!RootShell.dirExists(dataPath)) {
                return BackupResult.failure("App data not found: " + packageName);
            }

            // مرحله ۳: ساخت پوشه‌ی بکاپ
            updateProgress("Creating backup folder...", 8);
            String folderName = FileUtils.generateBackupFolderName(packageName, backupName);
            backupDir = new File(FileUtils.getBackupRootDir(context), folderName);
            if (!FileUtils.ensureDir(backupDir)) {
                return BackupResult.failure("Cannot create backup folder");
            }

            // مرحله ۴: گرفتن اطلاعات اپ
            updateProgress("Getting app info...", 10);
            int uid = RootShell.getAppUid(packageName);
            String selinuxContext = RootShell.getSelinuxContext(dataPath);

            // ساخت metadata اولیه
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

            // مرحله ۵: توقف اپ قبل از بکاپ
            updateProgress("Stopping app...", 12);
            RootShell.forceStopApp(packageName);
            // یه کم صبر کنیم تا کاملاً بسته بشه
            Thread.sleep(800);

            // مرحله ۶: بکاپ APK اصلی
            updateProgress("Backing up APK...", 18);
            if (appInfo.getApkPath() != null && !appInfo.getApkPath().isEmpty()) {
                File apkDest = new File(backupDir, "base.apk");
                String cmd = "cp " + RootShell.escapePath(appInfo.getApkPath())
                        + " " + RootShell.escapePath(apkDest.getAbsolutePath());
                RootShell.Result r = RootShell.run(cmd);
                if (r.success && apkDest.exists() && apkDest.length() > 0) {
                    meta.setHasApk(true);
                } else {
                    Log.w(TAG, "APK backup failed: " + r.allOutput());
                }
            }

            // مرحله ۷: بکاپ Split APKs (اگه باشه)
            updateProgress("Backing up split APKs...", 25);
            if (appInfo.hasSplits()) {
                File splitsDir = new File(backupDir, "splits");
                FileUtils.ensureDir(splitsDir);
                boolean allOk = true;
                for (String splitPath : appInfo.getSplitApkPaths()) {
                    File splitFile = new File(splitPath);
                    File dest = new File(splitsDir, splitFile.getName());
                    String cmd = "cp " + RootShell.escapePath(splitPath)
                            + " " + RootShell.escapePath(dest.getAbsolutePath());
                    RootShell.Result r = RootShell.run(cmd);
                    if (!r.success || !dest.exists()) {
                        allOk = false;
                        Log.w(TAG, "Split backup failed: " + r.allOutput());
                    }
                }
                meta.setHasSplitApks(allOk);
            }

            // مرحله ۸: بکاپ Internal Data ⭐ (با tar)
            updateProgress("Backing up internal data...", 40);
            File dataDir = new File(backupDir, "data");
            FileUtils.ensureDir(dataDir);
            
            String tarCmd = "cd " + dataPath
                    + " && tar -cf - --exclude='./cache' --exclude='./code_cache' --exclude='./lib' "
                    + " --warning=no-file-ignored "
                    + " . 2>/dev/null | tar -xf - -C "
                    + RootShell.escapePath(dataDir.getAbsolutePath());
            RootShell.Result r = RootShell.run(tarCmd);
            
            // چک می‌کنیم بکاپ موفق بود (با لیست کردن فایل‌ها)
            File[] backedUp = dataDir.listFiles();
            if (backedUp != null && backedUp.length > 0) {
                meta.setHasInternalData(true);
                Log.d(TAG, "Internal data backed up: " + backedUp.length + " items");
            } else {
                throw new Exception("Internal data backup failed - no files copied: " + r.allOutput());
            }

            // مرحله ۹: بکاپ Device-Protected Data (Direct Boot)
            updateProgress("Backing up device-protected data...", 55);
            String dePath = "/data/user_de/0/" + packageName;
            if (RootShell.dirExists(dePath)) {
                File deDir = new File(backupDir, "data_de");
                FileUtils.ensureDir(deDir);
                
                String tarDeCmd = "cd " + dePath
                        + " && tar -cf - --exclude='./cache' --exclude='./code_cache' "
                        + " --warning=no-file-ignored "
                        + " . 2>/dev/null | tar -xf - -C "
                        + RootShell.escapePath(deDir.getAbsolutePath());
                r = RootShell.run(tarDeCmd);
                
                File[] deFiles = deDir.listFiles();
                if (deFiles != null && deFiles.length > 0) {
                    meta.setHasDeviceProtectedData(true);
                    Log.d(TAG, "DE data backed up: " + deFiles.length + " items");
                } else {
                    Log.w(TAG, "DE data backup empty: " + r.allOutput());
                }
            }

            // مرحله ۱۰: بکاپ External Data
            updateProgress("Backing up external data...", 70);
            String extPath = "/sdcard/Android/data/" + packageName;
            if (RootShell.dirExists(extPath)) {
                File extDir = new File(backupDir, "ext_data");
                FileUtils.ensureDir(extDir);
                
                String tarExtCmd = "cd " + extPath
                        + " && tar -cf - --exclude='./cache' --warning=no-file-ignored . 2>/dev/null"
                        + " | tar -xf - -C " + RootShell.escapePath(extDir.getAbsolutePath());
                r = RootShell.run(tarExtCmd);
                
                File[] extFiles = extDir.listFiles();
                if (extFiles != null && extFiles.length > 0) {
                    meta.setHasExternalData(true);
                    Log.d(TAG, "External data backed up: " + extFiles.length + " items");
                } else {
                    Log.w(TAG, "External data backup empty: " + r.allOutput());
                }
            }

            // مرحله ۱۱: بکاپ OBB Files (داده‌های گیم)
            updateProgress("Backing up OBB files...", 82);
            String obbPath = "/sdcard/Android/obb/" + packageName;
            if (RootShell.dirExists(obbPath)) {
                File obbDir = new File(backupDir, "obb");
                FileUtils.ensureDir(obbDir);
                
                String tarObbCmd = "cd " + obbPath
                        + " && tar -cf - --warning=no-file-ignored . 2>/dev/null"
                        + " | tar -xf - -C " + RootShell.escapePath(obbDir.getAbsolutePath());
                r = RootShell.run(tarObbCmd);
                
                File[] obbFiles = obbDir.listFiles();
                if (obbFiles != null && obbFiles.length > 0) {
                    meta.setHasObb(true);
                    Log.d(TAG, "OBB backed up: " + obbFiles.length + " items");
                } else {
                    Log.w(TAG, "OBB backup empty: " + r.allOutput());
                }
            }

            // مرحله ۱۲: محاسبه‌ی سایز کل
            updateProgress("Calculating size...", 92);
            long totalSize = FileUtils.getFolderSize(backupDir);
            meta.setTotalSize(totalSize);

            // مرحله ۱۳: تنظیم permissions پوشه‌ی بکاپ
            updateProgress("Fixing permissions...", 95);
            String chmodCmd = "chmod -R 755 " + RootShell.escapePath(backupDir.getAbsolutePath());
            RootShell.run(chmodCmd);

            // مرحله ۱۴: نوشتن metadata.json
            updateProgress("Writing metadata...", 98);
            File metaFile = new File(backupDir, "metadata.json");
            FileUtils.writeString(metaFile, meta.toJson().toString(2));

            updateProgress("Backup complete!", 100);

            BackupResult result = BackupResult.success(
                    "Backup completed successfully (" + FileUtils.formatSize(totalSize) + ")",
                    backupDir.getAbsolutePath()
            );
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Backup failed", e);
            // پاک کردن بکاپ ناقص
            if (backupDir != null && backupDir.exists()) {
                FileUtils.deleteRecursive(backupDir);
            }
            return BackupResult.failure("Backup failed: " + e.getMessage(), e.toString());
        }
    }
}
