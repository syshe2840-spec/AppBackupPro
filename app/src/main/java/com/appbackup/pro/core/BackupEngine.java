package com.appbackup.pro.core;

import android.content.Context;
import android.os.Build;
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
        boolean appWasFrozen = false;

        Log.d(TAG, "");
        Log.d(TAG, "╔════════════════════════════════════════");
        Log.d(TAG, "║ BACKUP START: " + packageName);
        Log.d(TAG, "║ Android: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "╚════════════════════════════════════════");

        try {
            // مرحله ۱: روت
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                return BackupResult.failure("Root access denied");
            }

            // مرحله ۲: بررسی اپ
            updateProgress("Checking app...", 4);
            String dataPath = "/data/data/" + packageName;
            if (!RootShell.dirExists(dataPath)) {
                return BackupResult.failure("App data not found: " + packageName);
            }

            // مرحله ۳: ساخت پوشه
            updateProgress("Creating backup folder...", 6);
            String folderName = FileUtils.generateBackupFolderName(packageName, backupName);
            backupDir = new File(FileUtils.getBackupRootDir(context), folderName);
            if (!FileUtils.ensureDir(backupDir)) {
                return BackupResult.failure("Cannot create backup folder");
            }

            // مرحله ۴: اطلاعات اپ
            updateProgress("Getting app info...", 8);
            int uid = RootShell.getAppUid(packageName);
            int gid = getAppGid(packageName);
            String selinuxContext = RootShell.getSelinuxContext(dataPath);
            
            // ⭐ گرفتن sharedUserId و همه‌ی UID‌های مربوطه
            List<String> sharedPackages = getSharedUserPackages(packageName);
            Log.d(TAG, "UID=" + uid + " GID=" + gid);
            if (!sharedPackages.isEmpty()) {
                Log.d(TAG, "Shared user packages: " + sharedPackages);
            }

            BackupMeta meta = new BackupMeta();
            meta.setBackupId(UUID.randomUUID().toString());
            meta.setBackupName(backupName != null && !backupName.isEmpty() ? backupName : appInfo.getAppName());
            meta.setPackageName(packageName);
            meta.setAppName(appInfo.getAppName());
            meta.setVersionName(appInfo.getVersionName());
            meta.setVersionCode(appInfo.getVersionCode());
            meta.setCreatedAt(System.currentTimeMillis());
            meta.setUid(uid);
            meta.setGid(gid);
            meta.setSelinuxContext(selinuxContext);
            meta.setAndroidVersionAtBackup(Build.VERSION.SDK_INT);

            // ⭐⭐⭐ مرحله ۵: FREEZE اپ (به جای فقط force-stop)
            updateProgress("Freezing app for safe backup...", 10);
            appWasFrozen = AppFreezer.freeze(packageName);
            if (!appWasFrozen) {
                Log.w(TAG, "⚠ Could not freeze app, continuing anyway");
                // ادامه میدیم ولی هشدار می‌دیم
                RootShell.run("am force-stop " + packageName);
                Thread.sleep(1000);
            }

            // ⭐⭐⭐ مرحله ۶: Database Checkpoint (مهم!)
            updateProgress("Checkpointing databases...", 12);
            int checkpointed = DatabaseHelper.checkpointAllDatabases(packageName);
            Log.d(TAG, "Checkpointed " + checkpointed + " databases");

            // مرحله ۷: APK
            updateProgress("Backing up APK...", 16);
            if (appInfo.getApkPath() != null && !appInfo.getApkPath().isEmpty()) {
                File apkDest = new File(backupDir, "base.apk");
                String apkCmd = "cp " + RootShell.escapePath(appInfo.getApkPath())
                        + " " + RootShell.escapePath(apkDest.getAbsolutePath());
                RootShell.Result apkR = RootShell.run(apkCmd);
                logResult("APK copy", apkR);
                if (apkR.success && apkDest.exists() && apkDest.length() > 0) {
                    meta.setHasApk(true);
                }
            }

            // مرحله ۸: Splits
            updateProgress("Backing up split APKs...", 22);
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
                    if (!r.success || !dest.exists()) allOk = false;
                }
                meta.setHasSplitApks(allOk);
            }

            // مرحله ۹: Native Libraries
            updateProgress("Backing up native libraries...", 26);
            backupNativeLibs(packageName, backupDir, meta);

            // مرحله ۱۰: Internal Data ⭐
            updateProgress("Backing up internal data...", 34);
            File dataDir = new File(backupDir, "data");
            FileUtils.ensureDir(dataDir);
            
            String cpCmd = "cp -rfL " + dataPath + "/. " + RootShell.escapePath(dataDir.getAbsolutePath() + "/");
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("Internal cp -rfL", r);
            
            File[] backedUp = dataDir.listFiles();
            int fileCount = backedUp != null ? backedUp.length : 0;
            
            if (fileCount > 0) {
                meta.setHasInternalData(true);
                Log.d(TAG, "✓ Internal: " + fileCount + " items");
            } else {
                throw new Exception("Internal data backup failed - no files copied");
            }

            // مرحله ۱۱: DE Data
            updateProgress("Backing up device-protected data...", 48);
            String dePath = "/data/user_de/0/" + packageName;
            if (RootShell.dirExists(dePath)) {
                File deDir = new File(backupDir, "data_de");
                FileUtils.ensureDir(deDir);
                String deCmd = "cp -rfL " + dePath + "/. " + RootShell.escapePath(deDir.getAbsolutePath() + "/");
                RootShell.Result deR = RootShell.run(deCmd);
                File[] deFiles = deDir.listFiles();
                if (deFiles != null && deFiles.length > 0) {
                    meta.setHasDeviceProtectedData(true);
                }
            }

            // مرحله ۱۲: External Data
            updateProgress("Backing up external data...", 58);
            String extPath = "/sdcard/Android/data/" + packageName;
            if (RootShell.dirExists(extPath)) {
                File extDir = new File(backupDir, "ext_data");
                FileUtils.ensureDir(extDir);
                String extCmd = "cp -rfL " + extPath + "/. " + RootShell.escapePath(extDir.getAbsolutePath() + "/");
                RootShell.run(extCmd);
                File[] extFiles = extDir.listFiles();
                if (extFiles != null && extFiles.length > 0) {
                    meta.setHasExternalData(true);
                }
            }

            // مرحله ۱۳: OBB
            updateProgress("Backing up OBB files...", 68);
            String obbPath = "/sdcard/Android/obb/" + packageName;
            if (RootShell.dirExists(obbPath)) {
                File obbDir = new File(backupDir, "obb");
                FileUtils.ensureDir(obbDir);
                String obbCmd = "cp -rfL " + obbPath + "/. " + RootShell.escapePath(obbDir.getAbsolutePath() + "/");
                RootShell.run(obbCmd);
                File[] obbFiles = obbDir.listFiles();
                if (obbFiles != null && obbFiles.length > 0) {
                    meta.setHasObb(true);
                }
            }

            // مرحله ۱۴: KeyStore
            updateProgress("Backing up keystore keys...", 76);
            backupKeystore(backupDir, meta, uid);
            
            // ⭐ همینطور KeyStore برای shared packages
            for (String sharedPkg : sharedPackages) {
                int sharedUid = RootShell.getAppUid(sharedPkg);
                if (sharedUid > 0 && sharedUid != uid) {
                    Log.d(TAG, "Backing up keystore for shared package: " + sharedPkg);
                    backupKeystore(backupDir, meta, sharedUid);
                }
            }

            // مرحله ۱۵: Permissions
            updateProgress("Backing up permissions...", 82);
            int permsCount = PermissionsManager.backupPermissions(packageName, backupDir);
            if (permsCount > 0) {
                meta.setHasPermissions(true);
                meta.setPermissionsCount(permsCount);
            }

            // مرحله ۱۶: محاسبه‌ی سایز
            updateProgress("Calculating size...", 90);
            long totalSize = FileUtils.getFolderSize(backupDir);
            meta.setTotalSize(totalSize);

            // مرحله ۱۷: permissions پوشه
            updateProgress("Fixing permissions...", 94);
            RootShell.run("chmod -R 755 " + RootShell.escapePath(backupDir.getAbsolutePath()));

            // مرحله ۱۸: metadata
            updateProgress("Writing metadata...", 97);
            File metaFile = new File(backupDir, "metadata.json");
            FileUtils.writeString(metaFile, meta.toJson().toString(2));

            // ⭐⭐⭐ مرحله ۱۹: UNFREEZE اپ (مهم! اپ رو برگردون به حالت عادی)
            updateProgress("Unfreezing app...", 99);
            if (appWasFrozen) {
                AppFreezer.unfreeze(packageName);
            }

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
            
            // ⭐ مهم: اگه اپ freeze شده، unfreezeش کن قبل از خروج
            if (appWasFrozen) {
                Log.d(TAG, "Unfreezing after error...");
                AppFreezer.unfreeze(packageName);
            }
            
            if (backupDir != null && backupDir.exists()) {
                FileUtils.deleteRecursive(backupDir);
            }
            return BackupResult.failure("Backup failed: " + e.getMessage(), e.toString());
        }
    }

    /**
     * ⭐ پیدا کردن همه‌ی package‌هایی که sharedUserId با اپ دارن
     */
    private List<String> getSharedUserPackages(String packageName) {
        List<String> result = new ArrayList<>();
        try {
            // گرفتن sharedUserId اپ
            RootShell.Result r1 = RootShell.run(
                "dumpsys package " + packageName + " | grep -i 'sharedUser=' | head -1");
            
            if (!r1.success || r1.stdout.trim().isEmpty()) {
                return result;
            }
            
            // فرمت: sharedUser=SharedUserSetting{xxx com.example.shared/10042}
            String line = r1.stdout.trim();
            int eq = line.indexOf('=');
            if (eq < 0) return result;
            
            String sharedInfo = line.substring(eq + 1);
            // استخراج اسم shared user
            int braceStart = sharedInfo.indexOf('{');
            int braceEnd = sharedInfo.lastIndexOf('}');
            if (braceStart < 0 || braceEnd < 0) return result;
            
            String inner = sharedInfo.substring(braceStart + 1, braceEnd).trim();
            // فرمت: xxx com.example.shared/10042
            String[] parts = inner.split("\\s+");
            if (parts.length < 2) return result;
            
            String sharedUserName = parts[1];
            int slashIdx = sharedUserName.indexOf('/');
            if (slashIdx > 0) sharedUserName = sharedUserName.substring(0, slashIdx);
            
            Log.d(TAG, "Shared user name: " + sharedUserName);
            
            // پیدا کردن همه‌ی package‌هایی که این sharedUserName رو دارن
            RootShell.Result r2 = RootShell.run(
                "pm list packages | while read line; do " +
                "pkg=$(echo $line | sed 's/package://'); " +
                "if dumpsys package $pkg 2>/dev/null | grep -q 'sharedUser=.*" + sharedUserName + "'; then " +
                "echo $pkg; fi; done 2>/dev/null | head -10");
            
            if (r2.success && !r2.stdout.trim().isEmpty()) {
                for (String pkg : r2.stdout.trim().split("\n")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty() && !pkg.equals(packageName)) {
                        result.add(pkg);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot get shared packages: " + e.getMessage());
        }
        return result;
    }

    private void backupNativeLibs(String packageName, File backupDir, BackupMeta meta) {
        try {
            String[] libPaths = {
                "/data/app/" + packageName + "*/lib",
                "/data/data/" + packageName + "/lib"
            };
            
            for (String libPattern : libPaths) {
                String findCmd = "ls -d " + libPattern + " 2>/dev/null | head -1";
                RootShell.Result findR = RootShell.run(findCmd);
                
                if (findR.success && !findR.stdout.trim().isEmpty()) {
                    String libPath = findR.stdout.trim();
                    File libsDir = new File(backupDir, "native_libs");
                    FileUtils.ensureDir(libsDir);
                    
                    String cpCmd = "cp -rfL " + libPath + "/. " + RootShell.escapePath(libsDir.getAbsolutePath() + "/");
                    RootShell.run(cpCmd);
                    
                    File[] libs = libsDir.listFiles();
                    if (libs != null && libs.length > 0) {
                        meta.setHasNativeLibs(true);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Native libs backup error: " + e.getMessage());
        }
    }

    private int getAppGid(String packageName) {
        try {
            RootShell.Result r = RootShell.run("stat -c '%g' /data/data/" + packageName);
            if (r.success) {
                return Integer.parseInt(r.stdout.trim());
            }
        } catch (Exception e) {}
        return -1;
    }

    private void backupKeystore(File backupDir, BackupMeta meta, int uid) {
        try {
            File keystoreDir = new File(backupDir, "keystore");
            FileUtils.ensureDir(keystoreDir);
            
            Log.d(TAG, "─── KEYSTORE BACKUP for UID " + uid + " ───");
            
            List<String> foundFiles = new ArrayList<>();
            if (meta.getKeystoreFiles() != null) {
                for (String f : meta.getKeystoreFiles()) foundFiles.add(f);
            }
            
            String[] keystorePaths = {
                "/data/misc/keystore/user_0",
                "/data/misc/keystore"
            };
            
            for (String ksPath : keystorePaths) {
                if (!RootShell.dirExists(ksPath)) continue;
                
                String findCmd = "find " + ksPath + " -maxdepth 2 -name '" + uid + "_*' 2>/dev/null";
                RootShell.Result findR = RootShell.run(findCmd);
                
                if (findR.success && !findR.stdout.trim().isEmpty()) {
                    for (String filePath : findR.stdout.trim().split("\n")) {
                        filePath = filePath.trim();
                        if (filePath.isEmpty()) continue;
                        
                        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
                        if (foundFiles.contains(fileName)) continue;  // duplicate
                        
                        File destFile = new File(keystoreDir, fileName);
                        String cpCmd = "cp -f " + RootShell.escapePath(filePath)
                                + " " + RootShell.escapePath(destFile.getAbsolutePath());
                        RootShell.Result cpR = RootShell.run(cpCmd);
                        
                        if (cpR.success && destFile.exists() && destFile.length() > 0) {
                            foundFiles.add(fileName);
                        }
                    }
                }
            }
            
            String[] sqliteFiles = {
                "/data/misc/keystore/persistent.sqlite",
                "/data/misc/keystore/.masterkey"
            };
            for (String sqlPath : sqliteFiles) {
                if (RootShell.exists(sqlPath)) {
                    String name = "_shared_" + sqlPath.substring(sqlPath.lastIndexOf('/') + 1);
                    if (foundFiles.contains(name)) continue;
                    
                    File dest = new File(keystoreDir, name);
                    RootShell.Result r = RootShell.run("cp -f " + sqlPath + " " 
                        + RootShell.escapePath(dest.getAbsolutePath()));
                    if (r.success && dest.exists()) {
                        foundFiles.add(name);
                    }
                }
            }
            
            if (!foundFiles.isEmpty()) {
                meta.setHasKeystore(true);
                meta.setKeystoreFiles(foundFiles.toArray(new String[0]));
                Log.d(TAG, "✓ KeyStore total: " + foundFiles.size() + " files");
            }
        } catch (Exception e) {
            Log.w(TAG, "KeyStore backup error: " + e.getMessage());
        }
    }
}
