package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.utils.AppUtils;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;

/**
 * موتور اصلی ریستور - نسخه‌ی فول قوی قوی
 * Features:
 * - Dry-run mode (پیش‌نمایش بدون تغییر)
 * - Force mode (ادامه با وجود خطاها)
 * - Verification (چک قبل و بعد)
 * - Native libs restore
 * - Runtime permissions restore
 * - SELinux + ownership کامل
 */
public class RestoreEngine {
    private static final String TAG = "AppBackupPro_DEBUG";
    
    private static final String TMP_DIR = "/data/local/tmp/appbackup_install";

    private final Context context;
    private ProgressCallback progressCallback;
    
    // ⭐ Modes
    private boolean dryRun = false;
    private boolean forceMode = false;
    private boolean skipVerification = false;

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public RestoreEngine(Context context) {
        this.context = context;
    }

    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * ⭐ حالت dry-run: فقط چک می‌کنه چی قراره اتفاق بیفته، تغییر نمی‌ده
     */
    public RestoreEngine setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /**
     * ⭐ حالت force: حتی اگه خطا بده، ادامه می‌ده
     */
    public RestoreEngine setForceMode(boolean forceMode) {
        this.forceMode = forceMode;
        return this;
    }

    /**
     * ⭐ رد کردن verification (سریع‌تر ولی نا‌امن‌تر)
     */
    public RestoreEngine setSkipVerification(boolean skipVerification) {
        this.skipVerification = skipVerification;
        return this;
    }

    private void updateProgress(String message, int percent) {
        Log.d(TAG, "═══ RESTORE [" + percent + "%] " + message);
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

    public BackupResult restore(File backupDir, BackupMeta meta) {
        long startTime = System.currentTimeMillis();
        String packageName = meta.getPackageName();

        Log.d(TAG, "");
        Log.d(TAG, "╔════════════════════════════════════════");
        Log.d(TAG, "║ RESTORE START: " + packageName);
        Log.d(TAG, "║ DryRun: " + dryRun);
        Log.d(TAG, "║ Force: " + forceMode);
        Log.d(TAG, "║ SkipVerify: " + skipVerification);
        Log.d(TAG, "╚════════════════════════════════════════");

        try {
            // مرحله ۱: روت
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                return BackupResult.failure("Root access denied");
            }

            // ⭐ مرحله ۲: Pre-restore verification
            if (!skipVerification) {
                updateProgress("Verifying backup integrity...", 5);
                BackupVerifier.VerifyResult preVerify = BackupVerifier.verifyBackup(backupDir, meta);
                Log.d(TAG, "Pre-verify: " + preVerify.summary());
                
                if (!preVerify.success && !forceMode) {
                    return BackupResult.failure("Backup verification failed:\n" + preVerify.summary());
                }
                
                if (!preVerify.success && forceMode) {
                    Log.w(TAG, "⚠ Force mode: continuing despite verification errors");
                }
            }

            // ⭐ مرحله ۳: Dry-run mode
            if (dryRun) {
                updateProgress("Dry-run analysis...", 50);
                String report = generateDryRunReport(backupDir, meta);
                Log.d(TAG, report);
                BackupResult result = BackupResult.success("DRY RUN COMPLETE\n\n" + report);
                result.setDurationMs(System.currentTimeMillis() - startTime);
                return result;
            }

            // مرحله ۴: نصب APK
            boolean appInstalled = AppUtils.isAppInstalled(context, packageName);
            Log.d(TAG, "App installed: " + appInstalled);
            
            if (!appInstalled) {
                updateProgress("App not installed. Installing APK...", 8);
                if (!meta.hasApk()) {
                    return BackupResult.failure("App not installed and no APK in backup");
                }
                BackupResult installResult = installApk(backupDir, meta);
                if (!installResult.isSuccess() && !forceMode) {
                    return installResult;
                }
                Thread.sleep(2000);
            }

            // مرحله ۵: UID/GID check
            updateProgress("Getting app UID...", 18);
            int newUid = RootShell.getAppUid(packageName);
            int originalUid = meta.getUid();
            Log.d(TAG, "UID: current=" + newUid + " backup=" + originalUid);
            
            if (newUid <= 0) {
                if (!forceMode) {
                    return BackupResult.failure("Cannot get app UID");
                }
                newUid = originalUid;  // در force mode، UID قبلی رو امتحان کنیم
            }

            // مرحله ۶: توقف اپ
            updateProgress("Stopping app...", 22);
            RootShell.run("am force-stop " + packageName);
            Thread.sleep(1000);

            // مرحله ۷: Internal Data
            if (meta.hasInternalData()) {
                updateProgress("Restoring internal data...", 28);
                File dataDir = new File(backupDir, "data");
                if (dataDir.exists()) {
                    BackupResult r = restoreInternalData(packageName, dataDir, newUid);
                    if (!r.isSuccess() && !forceMode) {
                        return r;
                    }
                }
            }

            // ⭐ مرحله ۸: Native Libraries (جدید)
            if (meta.hasNativeLibs()) {
                updateProgress("Restoring native libraries...", 38);
                restoreNativeLibs(packageName, backupDir, newUid);
            }

            // مرحله ۹: KeyStore
            if (meta.hasKeystore()) {
                updateProgress("Restoring keystore keys...", 46);
                restoreKeystore(backupDir, meta, newUid);
            }

            // مرحله ۱۰: DE Data
            if (meta.hasDeviceProtectedData()) {
                updateProgress("Restoring device-protected data...", 56);
                File deDir = new File(backupDir, "data_de");
                if (deDir.exists()) {
                    restoreDeData(packageName, deDir, newUid);
                }
            }

            // مرحله ۱۱: External Data
            if (meta.hasExternalData()) {
                updateProgress("Restoring external data...", 66);
                File extDir = new File(backupDir, "ext_data");
                if (extDir.exists()) {
                    restoreExternalData(packageName, extDir);
                }
            }

            // مرحله ۱۲: OBB
            if (meta.hasObb()) {
                updateProgress("Restoring OBB files...", 76);
                File obbDir = new File(backupDir, "obb");
                if (obbDir.exists()) {
                    restoreObb(packageName, obbDir);
                }
            }

            // مرحله ۱۳: SELinux + Ownership
            updateProgress("Fixing SELinux and ownership...", 84);
            fixSelinuxContexts(packageName);
            fixOwnership(packageName, newUid);

            // ⭐ مرحله ۱۴: Runtime Permissions (جدید)
            if (meta.hasPermissions()) {
                updateProgress("Restoring permissions...", 88);
                int restored = PermissionsManager.restorePermissions(packageName, backupDir);
                Log.d(TAG, "Restored " + restored + "/" + meta.getPermissionsCount() + " permissions");
            }

            // ⭐ مرحله ۱۵: Post-restore verification (جدید)
            String verifyReport = "";
            if (!skipVerification) {
                updateProgress("Verifying restore...", 94);
                BackupVerifier.VerifyResult postVerify = BackupVerifier.verifyRestore(packageName, meta);
                verifyReport = postVerify.summary();
                Log.d(TAG, "Post-verify: " + verifyReport);
                
                if (!postVerify.success && !forceMode) {
                    return BackupResult.failure("Restore verification failed:\n" + verifyReport);
                }
            }

            // مرحله ۱۶: نهایی
            updateProgress("Finalizing...", 98);
            RootShell.run("am force-stop " + packageName);

            updateProgress("Restore complete!", 100);
            
            Log.d(TAG, "");
            Log.d(TAG, "╔════════════════════════════════════════");
            Log.d(TAG, "║ RESTORE COMPLETE ✓");
            Log.d(TAG, "║ Duration: " + (System.currentTimeMillis() - startTime) + "ms");
            Log.d(TAG, "╚════════════════════════════════════════");

            String successMsg = "Restore completed successfully";
            if (!verifyReport.isEmpty() && !skipVerification) {
                successMsg += "\n\n" + verifyReport;
            }
            
            BackupResult result = BackupResult.success(successMsg);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "RESTORE FAILED", e);
            return BackupResult.failure("Restore failed: " + e.getMessage(), e.toString());
        }
    }

    /**
     * ⭐ گزارش Dry-Run (پیش‌نمایش)
     */
    private String generateDryRunReport(File backupDir, BackupMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 DRY-RUN REPORT\n");
        sb.append("═══════════════════════\n\n");
        sb.append("Package: ").append(meta.getPackageName()).append("\n");
        sb.append("App: ").append(meta.getAppName()).append("\n");
        sb.append("Version: ").append(meta.getVersionName()).append("\n");
        sb.append("Backup size: ").append(FileUtils.formatSize(meta.getTotalSize())).append("\n\n");
        
        sb.append("Will perform:\n");
        
        boolean installed = AppUtils.isAppInstalled(context, meta.getPackageName());
        if (!installed) {
            sb.append("  📦 Install APK from backup\n");
            if (meta.hasSplitApks()) sb.append("  📦 Install split APKs\n");
        } else {
            sb.append("  ⏭ App already installed (will replace data)\n");
        }
        
        if (meta.hasInternalData()) sb.append("  💾 Restore internal data\n");
        if (meta.hasNativeLibs()) sb.append("  📚 Restore native libraries\n");
        if (meta.hasKeystore()) sb.append("  🔑 Restore keystore (").append(
            meta.getKeystoreFiles() != null ? meta.getKeystoreFiles().length : 0
        ).append(" files)\n");
        if (meta.hasDeviceProtectedData()) sb.append("  🔒 Restore device-protected data\n");
        if (meta.hasExternalData()) sb.append("  📁 Restore external data\n");
        if (meta.hasObb()) sb.append("  🎮 Restore OBB files\n");
        if (meta.hasPermissions()) sb.append("  ✅ Restore ").append(meta.getPermissionsCount()).append(" permissions\n");
        
        sb.append("\nNo changes were made (dry-run mode).\n");
        sb.append("Run without dry-run to apply changes.");
        
        return sb.toString();
    }

    /**
     * نصب APK با کپی به /data/local/tmp/
     */
    private BackupResult installApk(File backupDir, BackupMeta meta) {
        try {
            File baseApk = new File(backupDir, "base.apk");
            if (!baseApk.exists()) {
                return BackupResult.failure("base.apk not found");
            }

            File splitsDir = new File(backupDir, "splits");
            boolean hasSplits = meta.hasSplitApks() && splitsDir.exists() && splitsDir.isDirectory();

            Log.d(TAG, "─── PREPARING INSTALL ───");
            RootShell.run("rm -rf " + TMP_DIR);
            RootShell.run("mkdir -p " + TMP_DIR);

            String tmpBaseApk = TMP_DIR + "/base.apk";
            RootShell.Result cpBase = RootShell.run("cp -f " 
                + RootShell.escapePath(baseApk.getAbsolutePath()) 
                + " " + tmpBaseApk);
            
            if (!cpBase.success) {
                return BackupResult.failure("Cannot copy APK to tmp: " + cpBase.allOutput());
            }

            RootShell.run("chmod 644 " + tmpBaseApk);
            RootShell.run("chown shell:shell " + tmpBaseApk);
            RootShell.run("restorecon " + tmpBaseApk);

            if (hasSplits) {
                File[] splits = splitsDir.listFiles();
                if (splits != null) {
                    for (File split : splits) {
                        String tmpSplit = TMP_DIR + "/" + split.getName();
                        RootShell.run("cp -f " 
                            + RootShell.escapePath(split.getAbsolutePath()) 
                            + " " + RootShell.escapePath(tmpSplit));
                        RootShell.run("chmod 644 " + RootShell.escapePath(tmpSplit));
                        RootShell.run("chown shell:shell " + RootShell.escapePath(tmpSplit));
                        RootShell.run("restorecon " + RootShell.escapePath(tmpSplit));
                    }
                }
            }

            BackupResult result;
            if (!hasSplits) {
                result = installSingleApkFromTmp(tmpBaseApk);
            } else {
                result = installSplitApksFromTmp(splitsDir);
            }

            RootShell.run("rm -rf " + TMP_DIR);
            return result;
            
        } catch (Exception e) {
            RootShell.run("rm -rf " + TMP_DIR);
            return BackupResult.failure("Install error: " + e.getMessage());
        }
    }

    private BackupResult installSingleApkFromTmp(String tmpApkPath) {
        try {
            String cmd = "pm install -r " + tmpApkPath;
            RootShell.Result r = RootShell.run(cmd);
            logResult("pm install", r);
            
            if (r.success && r.stdout.contains("Success")) {
                return BackupResult.success("APK installed");
            }
            
            String cmd2 = "cmd package install -r " + tmpApkPath;
            RootShell.Result r2 = RootShell.run(cmd2);
            logResult("cmd package install", r2);
            
            if (r2.success && (r2.stdout.contains("Success") || r2.stderr.contains("Success"))) {
                return BackupResult.success("APK installed");
            }
            
            return BackupResult.failure("APK install failed: " + r.allOutput());
        } catch (Exception e) {
            return BackupResult.failure("Install error: " + e.getMessage());
        }
    }

    private BackupResult installSplitApksFromTmp(File splitsDir) {
        try {
            File tmpBase = new File(TMP_DIR + "/base.apk");
            long totalSize = tmpBase.length();
            
            File[] splits = splitsDir.listFiles();
            if (splits != null) {
                for (File s : splits) totalSize += s.length();
            }

            String createCmd = "pm install-create -r -S " + totalSize;
            RootShell.Result createResult = RootShell.run(createCmd);
            if (!createResult.success) {
                return BackupResult.failure("Cannot create install session");
            }

            String output = createResult.stdout;
            int start = output.indexOf("[");
            int end = output.indexOf("]");
            if (start < 0 || end < 0) {
                return BackupResult.failure("Cannot parse session ID");
            }
            String sessionId = output.substring(start + 1, end);

            String tmpBaseApk = TMP_DIR + "/base.apk";
            RootShell.Result wr = RootShell.run("pm install-write -S " + tmpBase.length() + " "
                    + sessionId + " base " + tmpBaseApk);
            if (!wr.success) {
                RootShell.run("pm install-abandon " + sessionId);
                return BackupResult.failure("Cannot write base APK");
            }

            if (splits != null) {
                for (File split : splits) {
                    String splitName = split.getName().replace(".apk", "");
                    String tmpSplit = TMP_DIR + "/" + split.getName();
                    File tmpSplitFile = new File(tmpSplit);
                    
                    RootShell.Result sr = RootShell.run("pm install-write -S " + tmpSplitFile.length() + " "
                            + sessionId + " " + splitName + " " + RootShell.escapePath(tmpSplit));
                    if (!sr.success) {
                        RootShell.run("pm install-abandon " + sessionId);
                        return BackupResult.failure("Cannot write split: " + sr.allOutput());
                    }
                }
            }

            RootShell.Result commitResult = RootShell.run("pm install-commit " + sessionId);
            if (!commitResult.success || !commitResult.stdout.contains("Success")) {
                return BackupResult.failure("Install commit failed: " + commitResult.allOutput());
            }

            return BackupResult.success("Split APKs installed");
        } catch (Exception e) {
            return BackupResult.failure("Split install error: " + e.getMessage());
        }
    }

    private BackupResult restoreInternalData(String packageName, File dataDir, int uid) {
        try {
            String targetPath = "/data/data/" + packageName;
            Log.d(TAG, "─── INTERNAL DATA RESTORE ───");

            RootShell.run("rm -rf " + targetPath + "/*");

            String cpCmd = "cp -rfL " + RootShell.escapePath(dataDir.getAbsolutePath() + "/.")
                    + " " + targetPath + "/";
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("Internal cp -rfL", r);

            RootShell.Result lsResult = RootShell.run("ls -A " + targetPath);
            if (lsResult.stdout.trim().isEmpty()) {
                return BackupResult.failure("Internal data restore failed - no files");
            }

            RootShell.run("chown -R " + uid + ":" + uid + " " + targetPath);
            Log.d(TAG, "✓ Internal data restored");
            return BackupResult.success("Internal data restored");
        } catch (Exception e) {
            return BackupResult.failure("Internal restore error: " + e.getMessage());
        }
    }

    /**
     * ⭐ ریستور Native Libraries
     */
    private void restoreNativeLibs(String packageName, File backupDir, int uid) {
        try {
            File libsDir = new File(backupDir, "native_libs");
            if (!libsDir.exists() || libsDir.listFiles() == null || libsDir.listFiles().length == 0) {
                return;
            }
            
            Log.d(TAG, "─── NATIVE LIBS RESTORE ───");
            
            // مسیر هدف
            String targetLibPath = "/data/data/" + packageName + "/lib";
            
            // اگه /data/data/<pkg>/lib یه symlink هست، ولش کن
            // فقط اگه پوشه‌ی واقعیه ریستور کن
            RootShell.Result statR = RootShell.run("[ -L " + targetLibPath + " ] && echo SYMLINK || echo DIR");
            if (statR.stdout.contains("SYMLINK")) {
                Log.d(TAG, "Native libs path is symlink, skipping (system manages it)");
                return;
            }
            
            // اگه پوشه نیست، ساختش
            if (!RootShell.dirExists(targetLibPath)) {
                RootShell.run("mkdir -p " + targetLibPath);
            }
            
            String cpCmd = "cp -rfL " + RootShell.escapePath(libsDir.getAbsolutePath() + "/.")
                    + " " + targetLibPath + "/";
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("Native libs cp", r);
            
            RootShell.run("chown -R " + uid + ":" + uid + " " + targetLibPath);
            RootShell.run("chmod -R 755 " + targetLibPath);
            
            Log.d(TAG, "✓ Native libs restored");
        } catch (Exception e) {
            Log.e(TAG, "Native libs restore error", e);
        }
    }

    private void restoreKeystore(File backupDir, BackupMeta meta, int newUid) {
        try {
            File keystoreDir = new File(backupDir, "keystore");
            if (!keystoreDir.exists() || !keystoreDir.isDirectory()) return;
            
            Log.d(TAG, "");
            Log.d(TAG, "─── KEYSTORE RESTORE ───");
            Log.d(TAG, "Old UID: " + meta.getUid() + " New UID: " + newUid);
            
            File[] ksFiles = keystoreDir.listFiles();
            if (ksFiles == null || ksFiles.length == 0) return;
            
            int oldUid = meta.getUid();
            
            String keystoreTarget = "/data/misc/keystore/user_0";
            if (!RootShell.dirExists(keystoreTarget)) {
                keystoreTarget = "/data/misc/keystore";
            }
            
            RootShell.run("stop keystore 2>/dev/null");
            RootShell.run("stop keystore2 2>/dev/null");
            Thread.sleep(800);
            
            int restored = 0;
            for (File ksFile : ksFiles) {
                String fileName = ksFile.getName();
                
                if (fileName.startsWith("_shared_")) {
                    String realName = fileName.substring("_shared_".length());
                    String destPath = "/data/misc/keystore/" + realName;
                    String cpCmd = "cp -f " + RootShell.escapePath(ksFile.getAbsolutePath())
                            + " " + RootShell.escapePath(destPath);
                    RootShell.Result cpR = RootShell.run(cpCmd);
                    if (cpR.success) {
                        RootShell.run("chmod 600 " + RootShell.escapePath(destPath));
                        RootShell.run("chown keystore:keystore " + RootShell.escapePath(destPath) + " 2>/dev/null");
                        RootShell.run("restorecon " + RootShell.escapePath(destPath));
                        restored++;
                    }
                    continue;
                }
                
                String newFileName = fileName;
                if (oldUid > 0 && newUid != oldUid && fileName.startsWith(oldUid + "_")) {
                    newFileName = newUid + fileName.substring(String.valueOf(oldUid).length());
                    Log.d(TAG, "Remap: " + fileName + " → " + newFileName);
                }
                
                String destPath = keystoreTarget + "/" + newFileName;
                String cpCmd = "cp -f " + RootShell.escapePath(ksFile.getAbsolutePath())
                        + " " + RootShell.escapePath(destPath);
                RootShell.Result cpR = RootShell.run(cpCmd);
                
                if (cpR.success) {
                    RootShell.run("chmod 600 " + RootShell.escapePath(destPath));
                    RootShell.run("chown keystore:keystore " + RootShell.escapePath(destPath) + " 2>/dev/null");
                    RootShell.run("chown system:system " + RootShell.escapePath(destPath) + " 2>/dev/null");
                    RootShell.run("restorecon " + RootShell.escapePath(destPath));
                    restored++;
                }
            }
            
            RootShell.run("start keystore 2>/dev/null");
            RootShell.run("start keystore2 2>/dev/null");
            Thread.sleep(1500);
            
            Log.d(TAG, "✓ Keystore: " + restored + "/" + ksFiles.length);
        } catch (Exception e) {
            Log.e(TAG, "Keystore restore error", e);
        }
    }

    private void restoreDeData(String packageName, File deDir, int uid) {
        try {
            String targetPath = "/data/user_de/0/" + packageName;
            Log.d(TAG, "─── DE DATA RESTORE ───");
            
            if (!RootShell.dirExists(targetPath)) {
                RootShell.run("mkdir -p " + targetPath);
            } else {
                RootShell.run("rm -rf " + targetPath + "/*");
            }

            String cpCmd = "cp -rfL " + RootShell.escapePath(deDir.getAbsolutePath() + "/.")
                    + " " + targetPath + "/";
            RootShell.run(cpCmd);
            RootShell.run("chown -R " + uid + ":" + uid + " " + targetPath);
        } catch (Exception e) {
            Log.e(TAG, "DE data restore error", e);
        }
    }

    private void restoreExternalData(String packageName, File extDir) {
        try {
            String targetPath = "/sdcard/Android/data/" + packageName;
            Log.d(TAG, "─── EXTERNAL DATA RESTORE ───");
            
            RootShell.run("mkdir -p " + targetPath);
            RootShell.run("rm -rf " + targetPath + "/*");

            String cpCmd = "cp -rfL " + RootShell.escapePath(extDir.getAbsolutePath() + "/.")
                    + " " + targetPath + "/";
            RootShell.run(cpCmd);
        } catch (Exception e) {
            Log.e(TAG, "External data restore error", e);
        }
    }

    private void restoreObb(String packageName, File obbDir) {
        try {
            String targetPath = "/sdcard/Android/obb/" + packageName;
            Log.d(TAG, "─── OBB RESTORE ───");
            
            RootShell.run("mkdir -p " + targetPath);
            RootShell.run("rm -rf " + targetPath + "/*");

            String cpCmd = "cp -rfL " + RootShell.escapePath(obbDir.getAbsolutePath() + "/.")
                    + " " + targetPath + "/";
            RootShell.run(cpCmd);
        } catch (Exception e) {
            Log.e(TAG, "OBB restore error", e);
        }
    }

    private void fixSelinuxContexts(String packageName) {
        Log.d(TAG, "─── SELINUX RESTORE ───");
        
        RootShell.run("restorecon -R /data/data/" + packageName);

        if (RootShell.dirExists("/data/user_de/0/" + packageName)) {
            RootShell.run("restorecon -R /data/user_de/0/" + packageName);
        }

        if (RootShell.dirExists("/sdcard/Android/data/" + packageName)) {
            RootShell.run("restorecon -R /sdcard/Android/data/" + packageName);
        }
        
        RootShell.run("restorecon -R /data/misc/keystore 2>/dev/null");
    }

    /**
     * ⭐ اصلاح ownership کامل برای همه‌ی مسیرها
     */
    private void fixOwnership(String packageName, int uid) {
        Log.d(TAG, "─── OWNERSHIP FIX ───");
        
        RootShell.run("chown -R " + uid + ":" + uid + " /data/data/" + packageName);
        
        if (RootShell.dirExists("/data/user_de/0/" + packageName)) {
            RootShell.run("chown -R " + uid + ":" + uid + " /data/user_de/0/" + packageName);
        }
        
        Log.d(TAG, "✓ Ownership fixed");
    }
                }
