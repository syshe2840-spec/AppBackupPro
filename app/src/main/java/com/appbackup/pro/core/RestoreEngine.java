package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.utils.AppUtils;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;

/**
 * موتور ریستور - نسخه‌ی نهایی فول قوی با همه‌ی feature ها
 */
public class RestoreEngine {
    private static final String TAG = "AppBackupPro_DEBUG";
    
    private static final String TMP_DIR = "/data/local/tmp/appbackup_install";

    private final Context context;
    private ProgressCallback progressCallback;
    
    private boolean dryRun = false;
    private boolean forceMode = false;
    private boolean skipVerification = false;
    private RestoreOptions options = new RestoreOptions(RestoreOptions.RestoreMode.FULL);

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public RestoreEngine(Context context) {
        this.context = context;
    }

    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    public RestoreEngine setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public RestoreEngine setForceMode(boolean forceMode) {
        this.forceMode = forceMode;
        return this;
    }

    public RestoreEngine setSkipVerification(boolean skipVerification) {
        this.skipVerification = skipVerification;
        return this;
    }

    /**
     * ⭐ تنظیم گزینه‌های ریستور (Selective Restore)
     */
    public RestoreEngine setOptions(RestoreOptions options) {
        this.options = options != null ? options : new RestoreOptions(RestoreOptions.RestoreMode.FULL);
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
        RestoreSnapshot snapshot = null;
        int userId = options.getUserId();

        Log.d(TAG, "");
        Log.d(TAG, "╔════════════════════════════════════════");
        Log.d(TAG, "║ RESTORE START: " + packageName);
        Log.d(TAG, "║ Mode: " + options.getDescription());
        Log.d(TAG, "║ User: " + userId);
        Log.d(TAG, "║ DryRun: " + dryRun);
        Log.d(TAG, "║ Force: " + forceMode);
        Log.d(TAG, "║ SkipVerify: " + skipVerification);
        Log.d(TAG, "╚════════════════════════════════════════");

        try {
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                return BackupResult.failure("Root access denied");
            }

            // Pre-restore verification
            if (!skipVerification) {
                updateProgress("Verifying backup integrity...", 4);
                BackupVerifier.VerifyResult preVerify = BackupVerifier.verifyBackup(backupDir, meta);
                
                if (!preVerify.success && !forceMode) {
                    return BackupResult.failure("Backup verification failed:\n" + preVerify.summary());
                }
                
                updateProgress("Checking file integrity (SHA256)...", 6);
                boolean integrityOk = IntegrityChecker.verifyChecksums(backupDir);
                if (!integrityOk && !forceMode) {
                    return BackupResult.failure("Backup is corrupted (SHA256 mismatch)");
                }
            }

            // Dry-run mode
            if (dryRun) {
                updateProgress("Dry-run analysis...", 50);
                String report = generateDryRunReport(backupDir, meta);
                BackupResult result = BackupResult.success("DRY RUN COMPLETE\n\n" + report);
                result.setDurationMs(System.currentTimeMillis() - startTime);
                return result;
            }

            // Snapshot
            updateProgress("Creating safety snapshot...", 8);
            snapshot = new RestoreSnapshot(packageName);
            if (!snapshot.create()) {
                Log.w(TAG, "⚠ Snapshot creation failed");
                snapshot = null;
            }

            // ⭐ نصب APK (اگه گزینه فعاله)
            if (options.isRestoreApk()) {
                boolean appInstalled = MultiUserHelper.isAppInstalledForUser(packageName, userId);
                Log.d(TAG, "App installed for user " + userId + ": " + appInstalled);
                
                if (!appInstalled) {
                    updateProgress("Installing APK...", 12);
                    if (!meta.hasApk()) {
                        if (!forceMode) {
                            return BackupResult.failure("App not installed and no APK in backup");
                        }
                    } else {
                        BackupResult installResult = installApk(backupDir, meta, userId);
                        if (!installResult.isSuccess() && !forceMode) {
                            return installResult;
                        }
                        Thread.sleep(2000);
                    }
                }
            }

            // UID check
            updateProgress("Getting app UID...", 22);
            int newUid = MultiUserHelper.getAppUidForUser(packageName, userId);
            int originalUid = meta.getUid();
            Log.d(TAG, "UID: current=" + newUid + " backup=" + originalUid);
            
            if (newUid <= 0) {
                if (!forceMode) {
                    return BackupResult.failure("Cannot get app UID for user " + userId);
                }
                newUid = originalUid;
            }

            // توقف اپ
            updateProgress("Stopping app...", 26);
            RootShell.run("am force-stop --user " + userId + " " + packageName + " 2>/dev/null");
            RootShell.run("am force-stop " + packageName);
            Thread.sleep(1000);

            // ⭐ Internal Data (با selective options)
            if (meta.hasInternalData() && (options.isRestoreInternalData() 
                    || options.isRestoreDatabases() 
                    || options.isRestoreSharedPrefs() 
                    || options.isRestoreFiles())) {
                updateProgress("Restoring internal data...", 32);
                File dataDir = new File(backupDir, "data");
                if (dataDir.exists()) {
                    BackupResult r = restoreInternalDataSelective(packageName, dataDir, newUid, userId);
                    if (!r.isSuccess() && !forceMode) {
                        return r;
                    }
                }
            }

            // Native Libraries
            if (meta.hasNativeLibs() && options.isRestoreNativeLibs()) {
                updateProgress("Restoring native libraries...", 42);
                restoreNativeLibs(packageName, backupDir, newUid, userId);
            }

            // KeyStore
            if (meta.hasKeystore() && options.isRestoreKeystore()) {
                updateProgress("Restoring keystore keys...", 50);
                restoreKeystore(backupDir, meta, newUid, userId);
            }

            // DE Data
            if (meta.hasDeviceProtectedData() && options.isRestoreDeData()) {
                updateProgress("Restoring device-protected data...", 60);
                File deDir = new File(backupDir, "data_de");
                if (deDir.exists()) {
                    restoreDeData(packageName, deDir, newUid, userId);
                }
            }

            // External Data
            if (meta.hasExternalData() && options.isRestoreExternalData()) {
                updateProgress("Restoring external data...", 70);
                File extDir = new File(backupDir, "ext_data");
                if (extDir.exists()) {
                    restoreExternalData(packageName, extDir, userId);
                }
            }

            // OBB
            if (meta.hasObb() && options.isRestoreObb()) {
                updateProgress("Restoring OBB files...", 80);
                File obbDir = new File(backupDir, "obb");
                if (obbDir.exists()) {
                    restoreObb(packageName, obbDir, userId);
                }
            }

            // SELinux + Ownership
            updateProgress("Fixing SELinux and ownership...", 86);
            fixSelinuxContexts(packageName, userId);
            fixOwnership(packageName, newUid, userId);

            // Permissions
            if (meta.hasPermissions() && options.isRestorePermissions()) {
                updateProgress("Restoring permissions...", 90);
                int restored = PermissionsManager.restorePermissions(packageName, backupDir);
                Log.d(TAG, "Restored " + restored + "/" + meta.getPermissionsCount() + " permissions");
            }

            // Post-restore verification
            String verifyReport = "";
            if (!skipVerification && !options.isPartial()) {
                // فقط برای FULL restore verify می‌کنیم (partial restore حالت متفاوتی داره)
                updateProgress("Verifying restore...", 94);
                BackupVerifier.VerifyResult postVerify = BackupVerifier.verifyRestore(packageName, meta);
                verifyReport = postVerify.summary();
                
                if (!postVerify.success && !forceMode) {
                    if (snapshot != null) {
                        Log.w(TAG, "Post-verify failed, rolling back...");
                        snapshot.rollback();
                        return BackupResult.failure("Restore verification failed (rolled back):\n" + verifyReport);
                    }
                    return BackupResult.failure("Restore verification failed:\n" + verifyReport);
                }
            }

            updateProgress("Finalizing...", 98);
            RootShell.run("am force-stop " + packageName);

            // Commit snapshot
            if (snapshot != null) {
                snapshot.commit();
            }

            updateProgress("Restore complete!", 100);
            
            Log.d(TAG, "");
            Log.d(TAG, "╔════════════════════════════════════════");
            Log.d(TAG, "║ RESTORE COMPLETE ✓");
            Log.d(TAG, "║ Mode: " + options.getDescription());
            Log.d(TAG, "║ Duration: " + (System.currentTimeMillis() - startTime) + "ms");
            Log.d(TAG, "╚════════════════════════════════════════");

            String successMsg = "Restore completed successfully";
            if (options.isPartial()) {
                successMsg += " (" + options.getDescription() + ")";
            }
            if (!verifyReport.isEmpty() && !skipVerification) {
                successMsg += "\n\n" + verifyReport;
            }
            
            BackupResult result = BackupResult.success(successMsg);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "RESTORE FAILED", e);
            
            String rollbackMsg = "";
            if (snapshot != null) {
                if (snapshot.rollback()) {
                    rollbackMsg = "\n\n✓ App restored to previous state (rollback successful)";
                } else {
                    rollbackMsg = "\n\n⚠ Rollback failed - app may be in inconsistent state";
                }
            }
            
            return BackupResult.failure("Restore failed: " + e.getMessage() + rollbackMsg, e.toString());
        }
    }

    /**
     * ⭐ ریستور Internal Data با گزینه‌های انتخابی
     */
    private BackupResult restoreInternalDataSelective(String packageName, File dataDir, int uid, int userId) {
        try {
            String targetPath = MultiUserHelper.getDataPath(packageName, userId);
            Log.d(TAG, "─── INTERNAL DATA RESTORE ───");
            Log.d(TAG, "Target: " + targetPath);
            Log.d(TAG, "Mode: " + options.getMode());

            // اگه partial هست، فقط subdir خاص رو ریستور کن
            if (options.isInternalDataPartial()) {
                String subdir = options.getPartialTargetSubdir();
                File sourceSubdir = new File(dataDir, subdir);
                
                if (!sourceSubdir.exists()) {
                    Log.w(TAG, "Source subdir not in backup: " + subdir);
                    return BackupResult.success("Skipped: " + subdir + " not in backup");
                }
                
                Log.d(TAG, "Partial restore: " + subdir);
                
                // فقط همون پوشه رو پاک کن
                String targetSubdir = targetPath + "/" + subdir;
                RootShell.run("rm -rf " + targetSubdir);
                RootShell.run("mkdir -p " + targetSubdir);
                
                String cpCmd = "cp -rfL " + RootShell.escapePath(sourceSubdir.getAbsolutePath() + "/.")
                        + " " + targetSubdir + "/";
                RootShell.Result r = RootShell.run(cpCmd);
                logResult("Partial cp: " + subdir, r);
                
                RootShell.run("chown -R " + uid + ":" + uid + " " + targetSubdir);
                
                Log.d(TAG, "✓ Partial internal data restored: " + subdir);
                return BackupResult.success("Restored: " + subdir);
            }
            
            // اگه options.isRestoreInternalData() هست ولی بخش‌ها انتخابی هستن (CUSTOM mode)
            if (options.getMode() == RestoreOptions.RestoreMode.CUSTOM) {
                Log.d(TAG, "Custom restore - selective subdirs");
                
                // برای هر subdir چک می‌کنیم
                String[] subdirs = {"databases", "shared_prefs", "files"};
                boolean[] flags = {
                    options.isRestoreDatabases(),
                    options.isRestoreSharedPrefs(),
                    options.isRestoreFiles()
                };
                
                for (int i = 0; i < subdirs.length; i++) {
                    if (!flags[i]) continue;
                    
                    File sourceSubdir = new File(dataDir, subdirs[i]);
                    if (!sourceSubdir.exists()) continue;
                    
                    String targetSubdir = targetPath + "/" + subdirs[i];
                    RootShell.run("rm -rf " + targetSubdir);
                    RootShell.run("mkdir -p " + targetSubdir);
                    
                    String cpCmd = "cp -rfL " + RootShell.escapePath(sourceSubdir.getAbsolutePath() + "/.")
                            + " " + targetSubdir + "/";
                    RootShell.run(cpCmd);
                    
                    Log.d(TAG, "  ✓ Restored: " + subdirs[i]);
                }
                
                RootShell.run("chown -R " + uid + ":" + uid + " " + targetPath);
                return BackupResult.success("Custom restore complete");
            }
            
            // FULL یا DATA_ONLY: همه چی رو ریستور کن
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

    private String generateDryRunReport(File backupDir, BackupMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 DRY-RUN REPORT\n");
        sb.append("═══════════════════════\n\n");
        sb.append("Package: ").append(meta.getPackageName()).append("\n");
        sb.append("App: ").append(meta.getAppName()).append("\n");
        sb.append("Version: ").append(meta.getVersionName()).append("\n");
        sb.append("Backup size: ").append(FileUtils.formatSize(meta.getTotalSize())).append("\n");
        sb.append("Mode: ").append(options.getDescription()).append("\n");
        sb.append("User: ").append(options.getUserId()).append("\n\n");
        
        sb.append("Will perform:\n");
        sb.append("  📸 Create safety snapshot\n");
        
        if (options.isRestoreApk()) {
            boolean installed = MultiUserHelper.isAppInstalledForUser(meta.getPackageName(), options.getUserId());
            if (!installed) {
                sb.append("  📦 Install APK\n");
                if (meta.hasSplitApks()) sb.append("  📦 Install split APKs\n");
            } else {
                sb.append("  ⏭ App already installed\n");
            }
        }
        
        if (options.isRestoreInternalData() && meta.hasInternalData()) {
            sb.append("  💾 Restore internal data\n");
        } else if (options.getMode() == RestoreOptions.RestoreMode.DATABASES_ONLY) {
            sb.append("  🗄 Restore databases only\n");
        } else if (options.getMode() == RestoreOptions.RestoreMode.SHARED_PREFS_ONLY) {
            sb.append("  ⚙️ Restore preferences only\n");
        } else if (options.getMode() == RestoreOptions.RestoreMode.FILES_ONLY) {
            sb.append("  📁 Restore files only\n");
        }
        
        if (options.isRestoreNativeLibs() && meta.hasNativeLibs()) sb.append("  📚 Restore native libraries\n");
        if (options.isRestoreKeystore() && meta.hasKeystore()) sb.append("  🔑 Restore keystore\n");
        if (options.isRestoreDeData() && meta.hasDeviceProtectedData()) sb.append("  🔒 Restore DE data\n");
        if (options.isRestoreExternalData() && meta.hasExternalData()) sb.append("  📁 Restore external data\n");
        if (options.isRestoreObb() && meta.hasObb()) sb.append("  🎮 Restore OBB\n");
        if (options.isRestorePermissions() && meta.hasPermissions()) sb.append("  ✅ Restore permissions\n");
        
        sb.append("  🛡 Verify integrity & rollback on failure\n");
        sb.append("\nNo changes were made (dry-run mode).");
        
        return sb.toString();
    }

    private BackupResult installApk(File backupDir, BackupMeta meta, int userId) {
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
                + RootShell.escapePath(baseApk.getAbsolutePath()) + " " + tmpBaseApk);
            
            if (!cpBase.success) {
                return BackupResult.failure("Cannot copy APK to tmp");
            }

            RootShell.run("chmod 644 " + tmpBaseApk);
            RootShell.run("chown shell:shell " + tmpBaseApk);
            RootShell.run("restorecon " + tmpBaseApk);

            if (hasSplits) {
                File[] splits = splitsDir.listFiles();
                if (splits != null) {
                    for (File split : splits) {
                        String tmpSplit = TMP_DIR + "/" + split.getName();
                        RootShell.run("cp -f " + RootShell.escapePath(split.getAbsolutePath()) 
                            + " " + RootShell.escapePath(tmpSplit));
                        RootShell.run("chmod 644 " + RootShell.escapePath(tmpSplit));
                        RootShell.run("chown shell:shell " + RootShell.escapePath(tmpSplit));
                        RootShell.run("restorecon " + RootShell.escapePath(tmpSplit));
                    }
                }
            }

            BackupResult result;
            if (!hasSplits) {
                result = installSingleApkFromTmp(tmpBaseApk, userId);
            } else {
                result = installSplitApksFromTmp(splitsDir, userId);
            }

            RootShell.run("rm -rf " + TMP_DIR);
            return result;
            
        } catch (Exception e) {
            RootShell.run("rm -rf " + TMP_DIR);
            return BackupResult.failure("Install error: " + e.getMessage());
        }
    }

    private BackupResult installSingleApkFromTmp(String tmpApkPath, int userId) {
        try {
            String userArg = (userId != 0) ? " --user " + userId : "";
            String cmd = "pm install -r" + userArg + " " + tmpApkPath;
            RootShell.Result r = RootShell.run(cmd);
            logResult("pm install", r);
            
            if (r.success && r.stdout.contains("Success")) {
                return BackupResult.success("APK installed");
            }
            
            String cmd2 = "cmd package install -r" + userArg + " " + tmpApkPath;
            RootShell.Result r2 = RootShell.run(cmd2);
            
            if (r2.success && (r2.stdout.contains("Success") || r2.stderr.contains("Success"))) {
                return BackupResult.success("APK installed");
            }
            
            return BackupResult.failure("APK install failed: " + r.allOutput());
        } catch (Exception e) {
            return BackupResult.failure("Install error: " + e.getMessage());
        }
    }

    private BackupResult installSplitApksFromTmp(File splitsDir, int userId) {
        try {
            File tmpBase = new File(TMP_DIR + "/base.apk");
            long totalSize = tmpBase.length();
            
            File[] splits = splitsDir.listFiles();
            if (splits != null) {
                for (File s : splits) totalSize += s.length();
            }

            String userArg = (userId != 0) ? " --user " + userId : "";
            String createCmd = "pm install-create -r" + userArg + " -S " + totalSize;
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

    private void restoreNativeLibs(String packageName, File backupDir, int uid, int userId) {
        try {
            File libsDir = new File(backupDir, "native_libs");
            if (!libsDir.exists() || libsDir.listFiles() == null || libsDir.listFiles().length == 0) {
                return;
            }
            
            Log.d(TAG, "─── NATIVE LIBS RESTORE ───");
            
            String targetLibPath = MultiUserHelper.getDataPath(packageName, userId) + "/lib";
            
            RootShell.Result statR = RootShell.run("[ -L " + targetLibPath + " ] && echo SYMLINK || echo DIR");
            if (statR.stdout.contains("SYMLINK")) {
                return;
            }
            
            if (!RootShell.dirExists(targetLibPath)) {
                RootShell.run("mkdir -p " + targetLibPath);
            }
            
            String cpCmd = "cp -rfL " + RootShell.escapePath(libsDir.getAbsolutePath() + "/.")
                    + " " + targetLibPath + "/";
            RootShell.run(cpCmd);
            RootShell.run("chown -R " + uid + ":" + uid + " " + targetLibPath);
            RootShell.run("chmod -R 755 " + targetLibPath);
        } catch (Exception e) {
            Log.e(TAG, "Native libs restore error", e);
        }
    }

    private void restoreKeystore(File backupDir, BackupMeta meta, int newUid, int userId) {
        try {
            File keystoreDir = new File(backupDir, "keystore");
            if (!keystoreDir.exists() || !keystoreDir.isDirectory()) return;
            
            Log.d(TAG, "─── KEYSTORE RESTORE for user " + userId + " ───");
            
            File[] ksFiles = keystoreDir.listFiles();
            if (ksFiles == null || ksFiles.length == 0) return;
            
            int oldUid = meta.getUid();
            
            String keystoreTarget = "/data/misc/keystore/user_" + userId;
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
                    RootShell.Result cpR = RootShell.run("cp -f " 
                        + RootShell.escapePath(ksFile.getAbsolutePath())
                        + " " + RootShell.escapePath(destPath));
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
                }
                
                String destPath = keystoreTarget + "/" + newFileName;
                RootShell.Result cpR = RootShell.run("cp -f " 
                    + RootShell.escapePath(ksFile.getAbsolutePath())
                    + " " + RootShell.escapePath(destPath));
                
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

    private void restoreDeData(String packageName, File deDir, int uid, int userId) {
        try {
            String targetPath = MultiUserHelper.getDeDataPath(packageName, userId);
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

    private void restoreExternalData(String packageName, File extDir, int userId) {
        try {
            String targetPath = (userId == 0) 
                ? "/sdcard/Android/data/" + packageName
                : "/storage/emulated/" + userId + "/Android/data/" + packageName;
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

    private void restoreObb(String packageName, File obbDir, int userId) {
        try {
            String targetPath = (userId == 0) 
                ? "/sdcard/Android/obb/" + packageName
                : "/storage/emulated/" + userId + "/Android/obb/" + packageName;
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

    private void fixSelinuxContexts(String packageName, int userId) {
        Log.d(TAG, "─── SELINUX RESTORE ───");
        
        String dataPath = MultiUserHelper.getDataPath(packageName, userId);
        RootShell.run("restorecon -R " + dataPath);

        String dePath = MultiUserHelper.getDeDataPath(packageName, userId);
        if (RootShell.dirExists(dePath)) {
            RootShell.run("restorecon -R " + dePath);
        }
        
        RootShell.run("restorecon -R /data/misc/keystore 2>/dev/null");
    }

    private void fixOwnership(String packageName, int uid, int userId) {
        Log.d(TAG, "─── OWNERSHIP FIX ───");
        
        String dataPath = MultiUserHelper.getDataPath(packageName, userId);
        RootShell.run("chown -R " + uid + ":" + uid + " " + dataPath);
        
        String dePath = MultiUserHelper.getDeDataPath(packageName, userId);
        if (RootShell.dirExists(dePath)) {
            RootShell.run("chown -R " + uid + ":" + uid + " " + dePath);
        }
    }
                                      }
