package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.utils.AppUtils;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;

public class RestoreEngine {
    private static final String TAG = "AppBackupPro_DEBUG";

    private final Context context;
    private ProgressCallback progressCallback;

    public interface ProgressCallback {
        void onProgress(String message, int percent);
    }

    public RestoreEngine(Context context) {
        this.context = context;
    }

    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
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
        Log.d(TAG, "║ Backup dir: " + backupDir.getAbsolutePath());
        Log.d(TAG, "╚════════════════════════════════════════");

        try {
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                Log.e(TAG, "✗ Root denied");
                return BackupResult.failure("Root access denied");
            }
            Log.d(TAG, "✓ Root OK");

            updateProgress("Verifying backup...", 5);
            if (!backupDir.exists() || !backupDir.isDirectory()) {
                return BackupResult.failure("Backup folder not found");
            }
            Log.d(TAG, "✓ Backup folder exists");

            // چک نصب بودن اپ
            boolean appInstalled = AppUtils.isAppInstalled(context, packageName);
            Log.d(TAG, "App installed: " + appInstalled);
            
            if (!appInstalled) {
                updateProgress("App not installed. Installing APK...", 10);
                if (!meta.hasApk()) {
                    return BackupResult.failure("App not installed and no APK in backup");
                }
                BackupResult installResult = installApk(backupDir, meta);
                if (!installResult.isSuccess()) {
                    return installResult;
                }
                Thread.sleep(2000);
            }

            // گرفتن UID جدید
            updateProgress("Getting app UID...", 25);
            int newUid = RootShell.getAppUid(packageName);
            Log.d(TAG, "New UID: " + newUid + " | Old UID was: " + meta.getUid());
            if (newUid <= 0) {
                return BackupResult.failure("Cannot get app UID");
            }

            // توقف اپ
            updateProgress("Stopping app...", 28);
            RootShell.Result stopR = RootShell.run("am force-stop " + packageName);
            logResult("am force-stop", stopR);
            Thread.sleep(800);

            // ریستور Internal Data
            if (meta.hasInternalData()) {
                updateProgress("Restoring internal data...", 35);
                File dataDir = new File(backupDir, "data");
                if (dataDir.exists()) {
                    BackupResult r = restoreInternalData(packageName, dataDir, newUid);
                    if (!r.isSuccess()) {
                        return r;
                    }
                }
            }

            // ریستور DE Data
            if (meta.hasDeviceProtectedData()) {
                updateProgress("Restoring device-protected data...", 55);
                File deDir = new File(backupDir, "data_de");
                if (deDir.exists()) {
                    restoreDeData(packageName, deDir, newUid);
                }
            }

            // ریستور External Data
            if (meta.hasExternalData()) {
                updateProgress("Restoring external data...", 70);
                File extDir = new File(backupDir, "ext_data");
                if (extDir.exists()) {
                    restoreExternalData(packageName, extDir);
                }
            }

            // ریستور OBB
            if (meta.hasObb()) {
                updateProgress("Restoring OBB files...", 82);
                File obbDir = new File(backupDir, "obb");
                if (obbDir.exists()) {
                    restoreObb(packageName, obbDir);
                }
            }

            // اصلاح SELinux contexts
            updateProgress("Fixing SELinux contexts...", 92);
            fixSelinuxContexts(packageName);

            updateProgress("Finalizing...", 98);
            RootShell.run("am force-stop " + packageName);

            updateProgress("Restore complete!", 100);
            
            Log.d(TAG, "");
            Log.d(TAG, "╔════════════════════════════════════════");
            Log.d(TAG, "║ RESTORE COMPLETE ✓");
            Log.d(TAG, "║ Duration: " + (System.currentTimeMillis() - startTime) + "ms");
            Log.d(TAG, "╚════════════════════════════════════════");

            BackupResult result = BackupResult.success("Restore completed successfully");
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "");
            Log.e(TAG, "╔════════════════════════════════════════");
            Log.e(TAG, "║ RESTORE FAILED ✗");
            Log.e(TAG, "║ Error: " + e.getMessage());
            Log.e(TAG, "╚════════════════════════════════════════", e);
            return BackupResult.failure("Restore failed: " + e.getMessage(), e.toString());
        }
    }

    /**
     * نصب APK
     */
    private BackupResult installApk(File backupDir, BackupMeta meta) {
        try {
            File baseApk = new File(backupDir, "base.apk");
            if (!baseApk.exists()) {
                return BackupResult.failure("base.apk not found in backup");
            }

            File splitsDir = new File(backupDir, "splits");
            boolean hasSplits = meta.hasSplitApks() && splitsDir.exists() && splitsDir.isDirectory();

            if (!hasSplits) {
                updateProgress("Installing APK...", 15);
                String cmd = "pm install -r " + RootShell.escapePath(baseApk.getAbsolutePath());
                RootShell.Result r = RootShell.run(cmd);
                logResult("pm install", r);
                if (!r.success || !r.stdout.contains("Success")) {
                    return BackupResult.failure("APK install failed: " + r.allOutput());
                }
            } else {
                updateProgress("Installing APK with splits...", 15);
                BackupResult r = installSplitApks(baseApk, splitsDir);
                if (!r.isSuccess()) {
                    return r;
                }
            }

            return BackupResult.success("APK installed");
        } catch (Exception e) {
            return BackupResult.failure("Install error: " + e.getMessage());
        }
    }

    /**
     * نصب APK با split‌ها
     */
    private BackupResult installSplitApks(File baseApk, File splitsDir) {
        try {
            long totalSize = baseApk.length();
            File[] splits = splitsDir.listFiles();
            if (splits != null) {
                for (File s : splits) {
                    totalSize += s.length();
                }
            }

            String createCmd = "pm install-create -r -S " + totalSize;
            RootShell.Result createResult = RootShell.run(createCmd);
            logResult("pm install-create", createResult);
            if (!createResult.success) {
                return BackupResult.failure("Cannot create install session: " + createResult.allOutput());
            }

            String output = createResult.stdout;
            int start = output.indexOf("[");
            int end = output.indexOf("]");
            if (start < 0 || end < 0 || end <= start) {
                return BackupResult.failure("Cannot parse session ID");
            }
            String sessionId = output.substring(start + 1, end);
            Log.d(TAG, "Session ID: " + sessionId);

            String writeBaseCmd = "pm install-write -S " + baseApk.length() + " "
                    + sessionId + " base " + RootShell.escapePath(baseApk.getAbsolutePath());
            RootShell.Result wr = RootShell.run(writeBaseCmd);
            logResult("pm install-write base", wr);
            if (!wr.success) {
                RootShell.run("pm install-abandon " + sessionId);
                return BackupResult.failure("Cannot write base APK: " + wr.allOutput());
            }

            if (splits != null) {
                for (File split : splits) {
                    String splitName = split.getName().replace(".apk", "");
                    String writeSplitCmd = "pm install-write -S " + split.length() + " "
                            + sessionId + " " + splitName + " "
                            + RootShell.escapePath(split.getAbsolutePath());
                    RootShell.Result sr = RootShell.run(writeSplitCmd);
                    logResult("pm install-write " + splitName, sr);
                    if (!sr.success) {
                        RootShell.run("pm install-abandon " + sessionId);
                        return BackupResult.failure("Cannot write split " + splitName + ": " + sr.allOutput());
                    }
                }
            }

            String commitCmd = "pm install-commit " + sessionId;
            RootShell.Result commitResult = RootShell.run(commitCmd);
            logResult("pm install-commit", commitResult);
            if (!commitResult.success || !commitResult.stdout.contains("Success")) {
                return BackupResult.failure("Install commit failed: " + commitResult.allOutput());
            }

            return BackupResult.success("Split APKs installed");
        } catch (Exception e) {
            return BackupResult.failure("Split install error: " + e.getMessage());
        }
    }

    /**
     * ⭐ ریستور Internal Data (با cp - مثل بکاپ)
     */
    private BackupResult restoreInternalData(String packageName, File dataDir, int uid) {
        try {
            String targetPath = "/data/data/" + packageName;
            
            Log.d(TAG, "");
            Log.d(TAG, "─── INTERNAL DATA RESTORE ───");
            Log.d(TAG, "Source: " + dataDir.getAbsolutePath());
            Log.d(TAG, "Dest:   " + targetPath);

            // پاک کردن داده‌ی فعلی
            RootShell.Result clearR = RootShell.run("rm -rf " + targetPath + "/*");
            logResult("Clear target", clearR);

            // 🔥 استفاده از cp -rfL مثل بکاپ
            String cpCmd = "cp -rfL " + RootShell.escapePath(dataDir.getAbsolutePath() + "/.")
                    + " " + targetPath + "/";
            Log.d(TAG, "CMD: " + cpCmd);
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("Internal cp -rfL", r);

            // چک کردن نتیجه با ls
            RootShell.Result lsResult = RootShell.run("ls -A " + targetPath);
            logResult("Verify: ls -A target", lsResult);
            
            if (lsResult.stdout.trim().isEmpty()) {
                Log.e(TAG, "✗ NO FILES RESTORED!");
                return BackupResult.failure("Internal data restore failed - no files. Check LogFox tag '" + TAG + "'");
            }

            // اصلاح ownership
            String chownCmd = "chown -R " + uid + ":" + uid + " " + targetPath;
            RootShell.Result chownR = RootShell.run(chownCmd);
            logResult("chown", chownR);

            Log.d(TAG, "✓ Internal data restored");
            return BackupResult.success("Internal data restored");
        } catch (Exception e) {
            Log.e(TAG, "Internal restore exception", e);
            return BackupResult.failure("Internal restore error: " + e.getMessage());
        }
    }

    /**
     * ریستور DE Data (با cp)
     */
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
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("DE cp", r);

            RootShell.run("chown -R " + uid + ":" + uid + " " + targetPath);
        } catch (Exception e) {
            Log.e(TAG, "DE data restore error", e);
        }
    }

    /**
     * ریستور External Data (با cp)
     */
    private void restoreExternalData(String packageName, File extDir) {
        try {
            String targetPath = "/sdcard/Android/data/" + packageName;
            
            Log.d(TAG, "─── EXTERNAL DATA RESTORE ───");
            
            RootShell.run("mkdir -p " + targetPath);
            RootShell.run("rm -rf " + targetPath + "/*");

            String cpCmd = "cp -rfL " + RootShell.escapePath(extDir.getAbsolutePath() + "/.")
                    + " " + targetPath + "/";
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("External cp", r);
        } catch (Exception e) {
            Log.e(TAG, "External data restore error", e);
        }
    }

    /**
     * ریستور OBB (با cp)
     */
    private void restoreObb(String packageName, File obbDir) {
        try {
            String targetPath = "/sdcard/Android/obb/" + packageName;
            
            Log.d(TAG, "─── OBB RESTORE ───");
            
            RootShell.run("mkdir -p " + targetPath);
            RootShell.run("rm -rf " + targetPath + "/*");

            String cpCmd = "cp -rfL " + RootShell.escapePath(obbDir.getAbsolutePath() + "/.")
                    + " " + targetPath + "/";
            RootShell.Result r = RootShell.run(cpCmd);
            logResult("OBB cp", r);
        } catch (Exception e) {
            Log.e(TAG, "OBB restore error", e);
        }
    }

    /**
     * اصلاح SELinux contexts (مهم!)
     */
    private void fixSelinuxContexts(String packageName) {
        Log.d(TAG, "─── SELINUX RESTORE ───");
        
        RootShell.Result r1 = RootShell.run("restorecon -R /data/data/" + packageName);
        logResult("restorecon /data/data", r1);

        if (RootShell.dirExists("/data/user_de/0/" + packageName)) {
            RootShell.Result r2 = RootShell.run("restorecon -R /data/user_de/0/" + packageName);
            logResult("restorecon /data/user_de", r2);
        }

        if (RootShell.dirExists("/sdcard/Android/data/" + packageName)) {
            RootShell.Result r3 = RootShell.run("restorecon -R /sdcard/Android/data/" + packageName);
            logResult("restorecon /sdcard/Android/data", r3);
        }
    }
                                                             }
