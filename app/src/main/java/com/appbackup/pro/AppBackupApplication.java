package com.appbackup.pro;

import android.app.Application;

import com.topjohnwu.superuser.Shell;

/**
 * Application class - تنظیمات اولیه‌ی libsu
 */
public class AppBackupApplication extends Application {

    static {
        // تنظیمات libsu - باید قبل از هر چیز انجام بشه
        Shell.enableVerboseLogging = false;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(60));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // پیش‌گرم کردن shell - تا اولین استفاده سریع‌تر باشه
        Shell.getShell(shell -> {
            // shell آماده‌ست
        });
    }
}