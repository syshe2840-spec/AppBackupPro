package com.appbackup.pro.core;

import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.util.List;

/**
 * موتور اجرای دستورات root با libsu
 * از یک persistent shell استفاده می‌کنه که خیلی سریع‌تره
 */
public class RootShell {
    private static final String TAG = "RootShell";

    // تنظیمات اولیه‌ی libsu - باید توی Application class صدا زده بشه
    static {
        Shell.enableVerboseLogging = false;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(60));
    }

    /**
     * نتیجه‌ی اجرای یک دستور
     */
    public static class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean success;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.success = exitCode == 0;
        }

        public String allOutput() {
            return stdout + (stderr.isEmpty() ? "" : "\n" + stderr);
        }
    }

    /**
     * چک می‌کنه که آیا گوشی روت هست
     */
    public static boolean isRootAvailable() {
        return Shell.isAppGrantedRoot() != null && Shell.isAppGrantedRoot();
    }

    /**
     * چک می‌کنه که اپ ما permission روت گرفته یا نه
     */
    public static boolean checkRootPermission() {
        try {
            Shell.Result result = Shell.cmd("id").exec();
            if (result.isSuccess()) {
                String output = String.join("\n", result.getOut());
                return output.contains("uid=0") || output.contains("root");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking root: " + e.getMessage());
        }
        return false;
    }

    /**
     * اجرای یک دستور root
     */
    public static Result run(String command) {
        try {
            Shell.Result result = Shell.cmd(command).exec();
            String stdout = String.join("\n", result.getOut());
            String stderr = String.join("\n", result.getErr());
            return new Result(result.getCode(), stdout, stderr);
        } catch (Exception e) {
            Log.e(TAG, "Error running command: " + command, e);
            return new Result(-1, "", e.toString());
        }
    }

    /**
     * اجرای چندین دستور پشت سر هم
     */
    public static Result runMultiple(String... commands) {
        try {
            Shell.Job job = Shell.cmd(commands);
            Shell.Result result = job.exec();
            String stdout = String.join("\n", result.getOut());
            String stderr = String.join("\n", result.getErr());
            return new Result(result.getCode(), stdout, stderr);
        } catch (Exception e) {
            Log.e(TAG, "Error running commands", e);
            return new Result(-1, "", e.toString());
        }
    }

    /**
     * اجرای دستور و throw کردن exception اگه fail بشه
     */
    public static void runOrThrow(String command) throws Exception {
        Result result = run(command);
        if (!result.success) {
            throw new Exception("Command failed: " + command + "\n" + result.allOutput());
        }
    }

    /**
     * چک می‌کنه که فایل یا پوشه‌ای روی سیستم وجود داره
     */
    public static boolean exists(String path) {
        Result result = run("[ -e '" + path + "' ] && echo OK");
        return result.success && result.stdout.contains("OK");
    }

    /**
     * چک می‌کنه که پوشه‌ای روی سیستم وجود داره
     */
    public static boolean dirExists(String path) {
        Result result = run("[ -d '" + path + "' ] && echo OK");
        return result.success && result.stdout.contains("OK");
    }

    /**
     * گرفتن UID یک اپ
     */
    public static int getAppUid(String packageName) {
        Result result = run("stat -c '%u' /data/data/" + packageName);
        if (result.success) {
            try {
                return Integer.parseInt(result.stdout.trim());
            } catch (NumberFormatException e) {
                Log.e(TAG, "Cannot parse UID: " + result.stdout);
            }
        }
        return -1;
    }

    /**
     * گرفتن SELinux context یک پوشه
     */
    public static String getSelinuxContext(String path) {
        Result result = run("ls -Zd '" + path + "' | awk '{print $1}'");
        if (result.success) {
            return result.stdout.trim();
        }
        return "";
    }

    /**
     * توقف اجباری یک اپ
     */
    public static boolean forceStopApp(String packageName) {
        Result result = run("am force-stop " + packageName);
        return result.success;
    }

    /**
     * گرفتن سایز یک پوشه به بایت
     */
    public static long getDirSize(String path) {
        Result result = run("du -sb '" + path + "' 2>/dev/null | cut -f1");
        if (result.success) {
            try {
                return Long.parseLong(result.stdout.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Escape کردن string برای استفاده توی shell command
     */
    public static String escapePath(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }
}