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

/**
 * موتور اصلی بکاپ - قلب اپ - نسخه‌ی فول قوی قوی
 * بکاپ کامل: APK + Splits + Internal + DE + External + OBB + Keystore + NativeLibs + Permissions
 */
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
        Log.d(TAG, "║ Android: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "╚════════════════════════════════════════");

        try {
            // مرحله ۱: روت
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                return BackupResult.failure("Root access denied");
            }
            Log.d(TAG, "✓ Root OK");

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
            Log.d(TAG, "Backup dir: " + backupDir.getAbsolutePath());

            // مرحله ۴: اطلاعات اپ
            updateProgress("Getting app info...", 8);
            int uid = RootShell.getAppUid(packageName);
            int gid = getAppGid(packageName);
            String selinuxContext = RootShell.getSelinuxContext(dataPath);
            Log.d(TAG, "UID=" + uid + " GID=" + gid + " SELinux=" + selinuxContext);

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

            // مرحله ۵: توقف اپ
            updateProgress("Stopping app...", 10);
            RootShell.run("am force-stop " + packageName);
            Thread.sleep(1000);  // 1 ثانیه برای اطمینان از بسته شدن کامل

            // مرحله ۶: APK
            updateProgress("Backing up APK...", 14);
            if (appInfo.getApkPath() != null && !appInfo.getApkPath().isEmpty()) {
                File apkDest = new File(backupDir, "base.apk");
                String apkCmd = "cp " + RootShell.escapePath(appInfo.getApkPath())
                        + " " + RootShell.escapePath(apkDest.getAbsolutePath());
                RootShell.Result apkR = RootShell.run(apkCmd);
                logResult("APK copy", apkR);
                if (apkR.success && apkDest.exists() && apkDest.length() > 0) {
                    meta.setHasApk(true);
                    Log.d(TAG, "✓ APK: " + apkDest.length() + " bytes");
                }
            }

            // مرحله ۷: Splits
            updateProgress("Backing up split APKs...", 20);
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
                if (allOk) Log.d(TAG, "✓ Splits backed up");
            }

            // مرحله ۸: ⭐ Native Libraries (جدید)
            updateProgress("Backing up native libraries...", 25);
            backupNativeLibs(packageName, backupDir, meta);

            // مرحله ۹: Internal Data ⭐
            updateProgress("Backing up internal data...", 32);
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
                Log.d(TAG, "✓ Internal: " + fileCount + " items");
            } else {
                throw new Exception("Internal data backup failed - no files copied");
            }

            // مرحله ۱۰: DE Data
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
                    Log.d(TAG, "✓ DE data: " + deFiles.length + " items");
                }
            }

            // مرحله ۱۱: External Data
            updateProgress("Backing up external data...", 60);
            String extPath = "/sdcard/Android/data/" + packageName;
            if (RootShell.dirExists(extPath)) {
                File extDir = new File(backupDir, "ext_data");
                FileUtils.ensureDir(extDir);
                String extCmd = "cp -rfL " + extPath + "/. " + RootShell.escapePath(extDir.getAbsolutePath() + "/");
                RootShell.Result extR = RootShell.run(extCmd);
                File[] extFiles = extDir.listFiles();
                if (extFiles != null && extFiles.length > 0) {
                    meta.setHasExternalData(true);
                    Log.d(TAG, "✓ Ext: " + extFiles.length + " items");
                }
            }

            // مرحله ۱۲: OBB
            updateProgress("Backing up OBB files...", 72);
            String obbPath = "/sdcard/Android/obb/" + packageName;
            if (RootShell.dirExists(obbPath)) {
                File obbDir = new File(backupDir, "obb");
                FileUtils.ensureDir(obbDir);
                String obbCmd = "cp -rfL " + obbPath + "/. " + RootShell.escapePath(obbDir.getAbsolutePath() + "/");
                RootShell.Result obbR = RootShell.run(obbCmd);
                File[] obbFiles = obbDir.listFiles();
                if (obbFiles != null && obbFiles.length > 0) {
                    meta.setHasObb(true);
                    Log.d(TAG, "✓ OBB: " + obbFiles.length + " items");
                }
            }

            // مرحله ۱۳: KeyStore
            updateProgress("Backing up keystore keys...", 80);
            backupKeystore(backupDir, meta, uid);

            // مرحله ۱۴: ⭐ Runtime Permissions (جدید)
            updateProgress("Backing up permissions...", 86);
            int permsCount = PermissionsManager.backupPermissions(packageName, backupDir);
            if (permsCount > 0) {
                meta.setHasPermissions(true);
                meta.setPermissionsCount(permsCount);
            }

            // مرحله ۱۵: محاسبه‌ی سایز
            updateProgress("Calculating size...", 92);
            long totalSize = FileUtils.getFolderSize(backupDir);
            meta.setTotalSize(totalSize);
            Log.d(TAG, "Total: " + FileUtils.formatSize(totalSize));

            // مرحله ۱۶: permissions
            updateProgress("Fixing permissions...", 95);
            RootShell.run("chmod -R 755 " + RootShell.escapePath(backupDir.getAbsolutePath()));

            // مرحله ۱۷: metadata
            updateProgress("Writing metadata...", 98);
            File metaFile = new File(backupDir, "metadata.json");
            FileUtils.writeString(metaFile, meta.toJson().toString(2));

            updateProgress("Backup complete!", 100);
            
            Log.d(TAG, "");
            Log.d(TAG, "╔════════════════════════════════════════");
            Log.d(TAG, "║ BACKUP COMPLETE ✓");
            Log.d(TAG, "║ Duration: " + (System.currentTimeMillis() - startTime) + "ms");
            Log.d(TAG, "║ Size: " + FileUtils.formatSize(totalSize));
            Log.d(TAG, "║ Components:");
            Log.d(TAG, "║   APK: " + meta.hasApk());
            Log.d(TAG, "║   Splits: " + meta.hasSplitApks());
            Log.d(TAG, "║   Internal: " + meta.hasInternalData());
            Log.d(TAG, "║   DE: " + meta.hasDeviceProtectedData());
            Log.d(TAG, "║   External: " + meta.hasExternalData());
            Log.d(TAG, "║   OBB: " + meta.hasObb());
            Log.d(TAG, "║   Keystore: " + meta.hasKeystore());
            Log.d(TAG, "║   NativeLibs: " + meta.hasNativeLibs());
            Log.d(TAG, "║   Permissions: " + meta.hasPermissions() + " (" + meta.getPermissionsCount() + ")");
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
     * ⭐ بکاپ Native Libraries
     */
    private void backupNativeLibs(String packageName, File backupDir, BackupMeta meta) {
        try {
            Log.d(TAG, "─── NATIVE LIBS BACKUP ───");
            
            // مسیرهای ممکن برای native libs
            String[] libPaths = {
                "/data/app/" + packageName + "*/lib",
                "/data/data/" + packageName + "/lib"
            };
            
            for (String libPattern : libPaths) {
                String findCmd = "ls -d " + libPattern + " 2>/dev/null | head -1";
                RootShell.Result findR = RootShell.run(findCmd);
                
                if (findR.success && !findR.stdout.trim().isEmpty()) {
                    String libPath = findR.stdout.trim();
                    Log.d(TAG, "Found native libs at: " + libPath);
                    
                    File libsDir = new File(backupDir, "native_libs");
                    FileUtils.ensureDir(libsDir);
                    
                    String cpCmd = "cp -rfL " + libPath + "/. " + RootShell.escapePath(libsDir.getAbsolutePath() + "/");
                    RootShell.Result cpR = RootShell.run(cpCmd);
                    logResult("Native libs cp", cpR);
                    
                    File[] libs = libsDir.listFiles();
                    if (libs != null && libs.length > 0) {
                        meta.setHasNativeLibs(true);
                        Log.d(TAG, "✓ Native libs: " + libs.length + " items");
                        return;
                    }
                }
            }
            
            Log.d(TAG, "No native libs found");
        } catch (Exception e) {
            Log.w(TAG, "Native libs backup error: " + e.getMessage());
        }
    }

    /**
     * گرفتن GID اپ
     */
    private int getAppGid(String packageName) {
        try {
            RootShell.Result r = RootShell.run("stat -c '%g' /data/data/" + packageName);
            if (r.success) {
                return Integer.parseInt(r.stdout.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot get GID: " + e.getMessage());
        }
        return -1;
    }

    /**
     * بکاپ KeyStore برای حل مشکل AEADBadTagException
     */
    private void backupKeystore(File backupDir, BackupMeta meta, int uid) {
        try {
            File keystoreDir = new File(backupDir, "keystore");
            FileUtils.ensureDir(keystoreDir);
            
            Log.d(TAG, "");
            Log.d(TAG, "─── KEYSTORE BACKUP ───");
            Log.d(TAG, "App UID: " + uid);
            
            List<String> foundFiles = new ArrayList<>();
            
            String[] keystorePaths = {
                "/data/misc/keystore/user_0",
                "/data/misc/keystore"
            };
            
            for (String ksPath : keystorePaths) {
                if (!RootShell.dirExists(ksPath)) continue;
                
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
                            Log.d(TAG, "  ✓ " + fileName);
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
                Log.d(TAG, "✓ KeyStore: " + foundFiles.size() + " files");
            } else {
                Log.d(TAG, "No KeyStore files for UID " + uid);
            }
        } catch (Exception e) {
            Log.w(TAG, "KeyStore backup error: " + e.getMessage());
        }
    }
    }
