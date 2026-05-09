package com.appbackup.pro.core;

import android.util.Log;

/**
 * مدیریت freeze کردن اپ‌ها برای بکاپ ایمن
 * با pm disable-user اپ کاملاً متوقف میشه - هیچ service یا alarm نمی‌تونه startش کنه
 */
public class AppFreezer {
    private static final String TAG = "AppBackupPro_DEBUG";

    /**
     * Freeze کامل اپ - بهترین راه برای بکاپ ایمن
     * با force-stop + disable-user، هیچ چیزی نمی‌تونه اپ رو wake کنه
     */
    public static boolean freeze(String packageName) {
        Log.d(TAG, "─── FREEZING APP: " + packageName + " ───");
        
        // مرحله ۱: force-stop (متوقف کردن فوری)
        RootShell.Result r1 = RootShell.run("am force-stop " + packageName);
        Log.d(TAG, "force-stop: exit=" + r1.exitCode);
        
        // یه کم صبر کنیم تا اپ کاملاً بسته بشه
        try { Thread.sleep(500); } catch (InterruptedException e) {}
        
        // مرحله ۲: kill کردن همه processهای مربوطه (محکم‌کاری)
        RootShell.Result r2 = RootShell.run("pkill -f " + packageName + " 2>/dev/null; true");
        Log.d(TAG, "pkill: exit=" + r2.exitCode);
        
        try { Thread.sleep(300); } catch (InterruptedException e) {}
        
        // مرحله ۳: disable-user (اپ کاملاً freeze میشه)
        // این کار اپ رو "غیرفعال" می‌کنه - هیچ service/alarm/receiver نمی‌تونه wake کنه
        RootShell.Result r3 = RootShell.run("pm disable-user --user 0 " + packageName);
        Log.d(TAG, "pm disable-user: exit=" + r3.exitCode + " out=" + r3.stdout.trim());
        
        boolean success = r3.success || r3.stdout.contains("disabled-user") 
                || r3.stdout.contains("new state: disabled");
        
        if (success) {
            Log.d(TAG, "✓ App frozen successfully");
        } else {
            Log.w(TAG, "⚠ App freeze may have failed: " + r3.allOutput());
        }
        
        // یه کم صبر تا سیستم همه چیزو settle کنه
        try { Thread.sleep(500); } catch (InterruptedException e) {}
        
        return success;
    }

    /**
     * Unfreeze کردن اپ - برگردوندن به حالت عادی
     */
    public static boolean unfreeze(String packageName) {
        Log.d(TAG, "─── UNFREEZING APP: " + packageName + " ───");
        
        RootShell.Result r = RootShell.run("pm enable --user 0 " + packageName);
        Log.d(TAG, "pm enable: exit=" + r.exitCode + " out=" + r.stdout.trim());
        
        boolean success = r.success || r.stdout.contains("enabled") 
                || r.stdout.contains("new state: default");
        
        if (success) {
            Log.d(TAG, "✓ App unfrozen successfully");
        } else {
            Log.w(TAG, "⚠ App unfreeze may have failed: " + r.allOutput());
        }
        
        return success;
    }

    /**
     * چک می‌کنه آیا اپ freeze شده
     */
    public static boolean isFrozen(String packageName) {
        RootShell.Result r = RootShell.run("pm list packages -d | grep -w " + packageName);
        return r.success && r.stdout.contains(packageName);
    }
}
