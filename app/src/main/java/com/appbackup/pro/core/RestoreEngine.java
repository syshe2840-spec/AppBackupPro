package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.utils.AppUtils;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;

/**
 * موتور اصلی ریستور - قلب ریکاوری
 * ریستور کامل: نصب APK + بازگردانی Internal + DE + External + OBB
 * + اصلاح ownership و SELinux context
 * از tar استفاده می‌کنیم تا با بکاپ سازگار باشه
 */
public class RestoreEngine {
    private static final String TAG = "RestoreEngine";

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
        Log.d(TAG, "[" + percent + "%] " + message);
        if (progressCallback != null) {
            progressCallback.onProgress(message, percent);
        }
    }

    /**
     * ریستور کامل یک بکاپ
     */
    public BackupResult restore(File backupDir, BackupMeta meta) {
        long startTime = System.currentTimeMillis();
        String packageName = meta.getPackageName();

        try {
            // مرحله ۱: بررسی روت
            updateProgress("Checking root access...", 2);
            if (!RootShell.checkRootPermission()) {
                return BackupResult.failure("Root access denied");
            }

            // مرحله ۲: بررسی پوشه‌ی بکاپ
            updateProgress("Verifying backup...", 5);
            if (!backupDir.exists() || !backupDir.isDirectory()) {
                return BackupResult.failure("Backup folder not found");
            }

            // مرحله ۳: چک کردن نصب بودن اپ - اگه نباشه نصب کن
            boolean appInstalled = AppUtils.isAppInstalled(context, packageName);
            if (!appInstalled) {
                updateProgress("App not installed. Installing APK...", 10);
                if (!meta.hasApk()) {
                    return BackupResult.failure("App not installed and no APK in backup");
                }
                BackupResult installResult = installApk(backupDir, meta);
                if (!installResult.isSuccess()) {
                    return installResult;
                }
                // یه کم صبر کنیم تا نصب کاملاً تموم بشه
                Thread.sleep(2000);
            }

            // مرحله ۴: گرفتن UID جدید (ممکنه بعد از نصب عوض شده باشه)
            updateProgress("Getting app UID...", 25);
            int newUid = RootShell.getAppUid(packageName);
            if (newUid <= 0) {
                return BackupResult.failure("Cannot get app UID");
            }
            Log.d(TAG, "New UID: " + newUid + " (old was: " + meta.getUid() + ")");

            // مرحله ۵: توقف اپ قبل از ریستور
            updateProgress("Stopping app...", 28);
            RootShell.forceStopApp(packageName);
            Thread.sleep(800);

            // مرحله ۶: ریستور Internal Data ⭐
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

            // مرحله ۷: ریستور Device-Protected Data
            if (meta.hasDeviceProtectedData()) {
                updateProgress("Restoring device-protected data...", 55);
                File deDir = new File(backupDir, "data_de");
                if (deDir.exists()) {
                    restoreDeData(packageName, deDir, newUid);
                }
            }

            // مرحله ۸: ریستور External Data
            if (meta.hasExternalData()) {
                updateProgress("Restoring external data...", 70);
                File extDir = new File(backupDir, "ext_data");
                if (extDir.exists()) {
                    restoreExternalData(packageName, extDir);
                }
            }

            // مرحله ۹: ریستور OBB Files
            if (meta.hasObb()) {
                updateProgress("Restoring OBB files...", 82);
                File obbDir = new File(backupDir, "obb");
                if (obbDir.exists()) {
                    restoreObb(packageName, obbDir);
                }
            }

            // مرحله ۱۰: اصلاح SELinux contexts (مهم!)
            updateProgress("Fixing SELinux contexts...", 92);
            fixSelinuxContexts(packageName);

            // مرحله ۱۱: تأیید نهایی
            updateProgress("Finalizing...", 98);
            // یه force-stop دیگه که اپ با دیتای جدید بالا بیاد
            RootShell.forceStopApp(packageName);

            updateProgress("Restore complete!", 100);

            BackupResult result = BackupResult.success("Restore completed successfully");
            result.setDurationMs(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Restore failed", e);
            return BackupResult.failure("Restore failed: " + e.getMessage(), e.toString());
        }
    }

    /**
     * نصب APK از روی بکاپ (شامل split APKs)
     */
    private BackupResult installApk(File backupDir, BackupMeta meta) {
        try {
            File baseApk = new File(backupDir, "base.apk");
            if (!baseApk.exists()) {
                return BackupResult.failure("base.apk not found in backup");
            }

            // اگه split APKs هست، باید با pm install-create استفاده کنیم
            File splitsDir = new File(backupDir, "splits");
            boolean hasSplits = meta.hasSplitApks() && splitsDir.exists() && splitsDir.isDirectory();

            if (!hasSplits) {
                // نصب ساده با pm install
                updateProgress("Installing APK...", 15);
                String cmd = "pm install -r " + RootShell.escapePath(baseApk.getAbsolutePath());
                RootShell.Result r = RootShell.run(cmd);
                if (!r.success || !r.stdout.contains("Success")) {
                    return BackupResult.failure("APK install failed: " + r.allOutput());
                }
            } else {
                // نصب پیچیده با split APKs
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
     * نصب APK با split‌های متعدد (با pm install-create)
     */
    private BackupResult installSplitApks(File baseApk, File splitsDir) {
        try {
            // محاسبه‌ی سایز کل
            long totalSize = baseApk.length();
            File[] splits = splitsDir.listFiles();
            if (splits != null) {
                for (File s : splits) {
                    totalSize += s.length();
                }
            }

            // مرحله ۱: ساخت session
            String createCmd = "pm install-create -r -S " + totalSize;
            RootShell.Result createResult = RootShell.run(createCmd);
            if (!createResult.success) {
                return BackupResult.failure("Cannot create install session: " + createResult.allOutput());
            }

            // استخراج session ID از خروجی
            String output = createResult.stdout;
            int start = output.indexOf("[");
            int end = output.indexOf("]");
            if (start < 0 || end < 0 || end <= start) {
                return BackupResult.failure("Cannot parse session ID");
            }
            String sessionId = output.substring(start + 1, end);

            // مرحله ۲: write base.apk به session
            String writeBaseCmd = "pm install-write -S " + baseApk.length() + " "
                    + sessionId + " base " + RootShell.escapePath(baseApk.getAbsolutePath());
            RootShell.Result wr = RootShell.run(writeBaseCmd);
            if (!wr.success) {
                RootShell.run("pm install-abandon " + sessionId);
                return BackupResult.failure("Cannot write base APK: " + wr.allOutput());
            }

            // مرحله ۳: write هر split
            if (splits != null) {
                for (File split : splits) {
                    String splitName = split.getName().replace(".apk", "");
                    String writeSplitCmd = "pm install-write -S " + split.length() + " "
                            + sessionId + " " + splitName + " "
                            + RootShell.escapePath(split.getAbsolutePath());
                    RootShell.Result sr = RootShell.run(writeSplitCmd);
                    if (!sr.success) {
                        RootShell.run("pm install-abandon " + sessionId);
                        return BackupResult.failure("Cannot write split " + splitName + ": " + sr.allOutput());
                    }
                }
            }

            // مرحله ۴: commit
            String commitCmd = "pm install-commit " + sessionId;
            RootShell.Result commitResult = RootShell.run(commitCmd);
            if (!commitResult.success || !commitResult.stdout.contains("Success")) {
                return BackupResult.failure("Install commit failed: " + commitResult.allOutput());
            }

            return BackupResult.success("Split APKs installed");
        } catch (Exception e) {
            return BackupResult.failure("Split install error: " + e.getMessage());
        }
    }

    /**
     * ریستور Internal Data به /data/data/<pkg> (با tar)
     */
    private BackupResult restoreInternalData(String packageName, File dataDir, int uid) {
        try {
            String targetPath = "/data/data/" + packageName;

            // پاک کردن داده‌ی فعلی
            RootShell.run("rm -rf " + targetPath + "/*");

            // ریستور با tar
            String tarCmd = "cd " + RootShell.escapePath(dataDir.getAbsolutePath())
                    + " && tar -cf - --warning=no-file-ignored . 2>/dev/null"
                    + " | tar -xf - -C " + targetPath;
            RootShell.Result r = RootShell.run(tarCmd);

            // چک با لیست فایل‌ها
            RootShell.Result lsResult = RootShell.run("ls -A " + targetPath + " | head -1");
            if (lsResult.stdout.trim().isEmpty()) {
                return BackupResult.failure("Internal data restore failed - no files: " + r.allOutput());
            }

            // اصلاح ownership (مهم!)
            RootShell.run("chown -R " + uid + ":" + uid + " " + targetPath);

            return BackupResult.success("Internal data restored");
        } catch (Exception e) {
            return BackupResult.failure("Internal restore error: " + e.getMessage());
        }
    }

    /**
     * ریستور Device-Protected Data (با tar)
     */
    private void restoreDeData(String packageName, File deDir, int uid) {
        try {
            String targetPath = "/data/user_de/0/" + packageName;
            
            if (!RootShell.dirExists(targetPath)) {
                RootShell.run("mkdir -p " + targetPath);
            } else {
                RootShell.run("rm -rf " + targetPath + "/*");
            }

            String tarCmd = "cd " + RootShell.escapePath(deDir.getAbsolutePath())
                    + " && tar -cf - --warning=no-file-ignored . 2>/dev/null"
                    + " | tar -xf - -C " + targetPath;
            RootShell.run(tarCmd);

            // اصلاح ownership
            RootShell.run("chown -R " + uid + ":" + uid + " " + targetPath);
        } catch (Exception e) {
            Log.e(TAG, "DE data restore error", e);
        }
    }

    /**
     * ریستور External Data به /sdcard/Android/data/<pkg> (با tar)
     */
    private void restoreExternalData(String packageName, File extDir) {
        try {
            String targetPath = "/sdcard/Android/data/" + packageName;
            RootShell.run("mkdir -p " + targetPath);
            RootShell.run("rm -rf " + targetPath + "/*");

            String tarCmd = "cd " + RootShell.escapePath(extDir.getAbsolutePath())
                    + " && tar -cf - --warning=no-file-ignored . 2>/dev/null"
                    + " | tar -xf - -C " + targetPath;
            RootShell.run(tarCmd);
        } catch (Exception e) {
            Log.e(TAG, "External data restore error", e);
        }
    }

    /**
     * ریستور OBB Files (با tar)
     */
    private void restoreObb(String packageName, File obbDir) {
        try {
            String targetPath = "/sdcard/Android/obb/" + packageName;
            RootShell.run("mkdir -p " + targetPath);
            RootShell.run("rm -rf " + targetPath + "/*");

            String tarCmd = "cd " + RootShell.escapePath(obbDir.getAbsolutePath())
                    + " && tar -cf - --warning=no-file-ignored . 2>/dev/null"
                    + " | tar -xf - -C " + targetPath;
            RootShell.run(tarCmd);
        } catch (Exception e) {
            Log.e(TAG, "OBB restore error", e);
        }
    }

    /**
     * اصلاح SELinux context (مهم! بدون این اپ کرش می‌کنه)
     */
    private void fixSelinuxContexts(String packageName) {
        // Internal data
        RootShell.run("restorecon -R /data/data/" + packageName);

        // Device-encrypted data
        if (RootShell.dirExists("/data/user_de/0/" + packageName)) {
            RootShell.run("restorecon -R /data/user_de/0/" + packageName);
        }

        // External data
        if (RootShell.dirExists("/sdcard/Android/data/" + packageName)) {
            RootShell.run("restorecon -R /sdcard/Android/data/" + packageName);
        }
    }
}
