package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.AppInfo;
import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;
import java.util.UUID;

public class BackupEngine {
    // 🔍 تگ منحصر به فرد برای فیلتر کردن توی LogFox/LogCat
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

    /**
     * یه helper برای log کردن نتیجه‌ی هر دستور
     */
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
        Log.d(TAG, "║ Time: " + System.currentTimeMillis());
        Log.d(TAG, "╚════════════════════════════════════════");

        try {
            // مرحله ۱: بررسی روت
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                Log.e(TAG, "✗ Root denied");
                return BackupResult.failure("Root access denied");
            }
            Log.d(TAG, "✓ Root OK");

            // مرحله ۲: بررسی نصب بودن اپ
            updateProgress("Checking app...", 5);
            String dataPath = "/data/data/" + packageName;
            
            // تست‌های متعدد برای پیدا کردن مشکل
            RootShell.Result lsTest = RootShell.run("ls -la " + dataPath);
            logResult("TEST: ls -la " + dataPath, lsTest);
            
            RootShell.Result whichTar = RootShell.run("which tar");
            logResult("TEST: which tar", whichTar);
            
            RootShell.Result tarVersion = RootShell.run("tar --version 2>&1 | head -1");
            logResult("TEST: tar --version", tarVersion);
            
            RootShell.Result whoami = RootShell.run("id");
            logResult("TEST: id (whoami)", whoami);
            
            if (!RootShell.dirExists(dataPath)) {
                Log.e(TAG, "✗ Data path not found: " + dataPath);
                return BackupResult.failure("App data not found: " + packageName);
            }
            Log.d(TAG, "✓ Data path exists");

            // مرحله ۳: ساخت پوشه‌ی بکاپ
            updateProgress("Creating backup folder...", 8);
            String folderName = FileUtils.generateBackupFolderName(packageName, backupName);
            backupDir = new File(FileUtils.getBackupRootDir(context), folderName);
            Log.d(TAG, "Backup dir: " + backupDir.getAbsolutePath());
            if (!FileUtils.ensureDir(backupDir)) {
                Log.e(TAG, "✗ Cannot create backup folder");
                return BackupResult.failure("Cannot create backup folder");
            }
            Log.d(TAG, "✓ Backup folder created");

            // مرحله ۴: گرفتن اطلاعات اپ
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
            RootShell.Result stopResult = RootShell.run("am force-stop " + packageName);
            logResult("am force-stop", stopResult);
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
                } else {
                    Log.w(TAG, "✗ APK backup failed");
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
                    logResult("Split copy: " + splitFile.getName(), splitR);
                    if (!splitR.success || !dest.exists()) {
                        allOk = false;
                    }
                }
                meta.setHasSplitApks(allOk);
            }

            // مرحله ۸: Internal Data ⭐
            updateProgress("Backing up internal data...", 40);
            File dataDir = new File(backupDir, "data");
            FileUtils.ensureDir(dataDir);
            
            Log.d(TAG, "");
            Log.d(TAG, "─── INTERNAL DATA BACKUP ───");
            Log.d(TAG, "Source: " + dataPath);
            Log.d(TAG, "Dest:   " + dataDir.getAbsolutePath());
            
            // 🔥 روش جدید: استفاده از cp بدون preserve xattr
            // این روش ساده‌تره و SELinux issues نداره
            String cpCmd = "cp -rfL " + dataPath + "/. " + RootShell.escapePath(dataDir.getAbsolutePath() + "/");
            Log.d(TAG, "CMD: " + cpCmd);
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("Internal cp -rfL", r);
            
            File[] backedUp = dataDir.listFiles();
            int fileCount = backedUp != null ? backedUp.length : 0;
            Log.d(TAG, "Files copied: " + fileCount);
            
            if (fileCount == 0) {
                // اگه روش اول کار نکرد، tar رو با sh -c امتحان می‌کنیم
                Log.w(TAG, "cp failed, trying tar with sh -c...");
                String tarCmd2 = "sh -c \"cd " + dataPath 
                        + " && tar -cf - --exclude=cache --exclude=code_cache --exclude=lib . 2>/dev/null"
                        + " | tar -xf - -C " + dataDir.getAbsolutePath() + " 2>/dev/null\"";
                Log.d(TAG, "CMD2: " + tarCmd2);
                RootShell.Result r2 = RootShell.run(tarCmd2);
                logResult("Internal tar via sh -c", r2);
                
                backedUp = dataDir.listFiles();
                fileCount = backedUp != null ? backedUp.length : 0;
                Log.d(TAG, "Files after tar: " + fileCount);
            }
            
            if (fileCount > 0) {
                meta.setHasInternalData(true);
                Log.d(TAG, "✓ Internal data backed up: " + fileCount + " items");
                if (backedUp != null) {
                    for (int i = 0; i < Math.min(5, backedUp.length); i++) {
                        Log.d(TAG, "  └─ " + backedUp[i].getName());
                    }
                }
            } else {
                Log.e(TAG, "✗ NO FILES COPIED!");
                throw new Exception("Internal data backup failed - no files copied. Check LogFox with tag '" + TAG + "'");
            }

            // مرحله ۹: DE Data
            updateProgress("Backing up device-protected data...", 55);
            String dePath = "/data/user_de/0/" + packageName;
            if (RootShell.dirExists(dePath)) {
                File deDir = new File(backupDir, "data_de");
                FileUtils.ensureDir(deDir);
                String deCmd = "cp -rfL " + dePath + "/. " + RootShell.escapePath(deDir.getAbsolutePath() + "/");
                RootShell.Result deR = RootShell.run(deCmd);
                logResult("DE data cp", deR);
                File[] deFiles = deDir.listFiles();
                if (deFiles != null && deFiles.length > 0) {
                    meta.setHasDeviceProtectedData(true);
                    Log.d(TAG, "✓ DE data: " + deFiles.length + " items");
                }
            } else {
                Log.d(TAG, "DE data path does not exist");
            }

            // مرحله ۱۰: External Data
            updateProgress("Backing up external data...", 70);
            String extPath = "/sdcard/Android/data/" + packageName;
            if (RootShell.dirExists(extPath)) {
                File extDir = new File(backupDir, "ext_data");
                FileUtils.ensureDir(extDir);
                String extCmd = "cp -rfL " + extPath + "/. " + RootShell.escapePath(extDir.getAbsolutePath() + "/");
                RootShell.Result extR = RootShell.run(extCmd);
                logResult("External data cp", extR);
                File[] extFiles = extDir.listFiles();
                if (extFiles != null && extFiles.length > 0) {
                    meta.setHasExternalData(true);
                    Log.d(TAG, "✓ External data: " + extFiles.length + " items");
                }
            } else {
                Log.d(TAG, "External data path does not exist");
            }

            // مرحله ۱۱: OBB
            updateProgress("Backing up OBB files...", 82);
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
            } else {
                Log.d(TAG, "OBB path does not exist");
            }

            // مرحله ۱۲: محاسبه‌ی سایز
            updateProgress("Calculating size...", 92);
            long totalSize = FileUtils.getFolderSize(backupDir);
            meta.setTotalSize(totalSize);
            Log.d(TAG, "Total size: " + totalSize + " bytes (" + FileUtils.formatSize(totalSize) + ")");

            // مرحله ۱۳: permissions
            updateProgress("Fixing permissions...", 95);
            RootShell.run("chmod -R 755 " + RootShell.escapePath(backupDir.getAbsolutePath()));

            // مرحله ۱۴: metadata
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
                    "Backup completed successfully (" + FileUtils.formatSize(totalSize) + ")",
                    backupDir.getAbsolutePath()
            );
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "");
            Log.e(TAG, "╔════════════════════════════════════════");
            Log.e(TAG, "║ BACKUP FAILED ✗");
            Log.e(TAG, "║ Error: " + e.getMessage());
            Log.e(TAG, "╚════════════════════════════════════════", e);
            
            if (backupDir != null && backupDir.exists()) {
                FileUtils.deleteRecursive(backupDir);
            }
            return BackupResult.failure("Backup failed: " + e.getMessage(), e.toString());
        }
    }
}
