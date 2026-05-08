package com.appbackup.pro;

import android.app.Application;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

public class AppBackupApplication extends Application {

    static {
        Shell.enableVerboseLogging = true;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER | Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(60));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // پیش‌گرم کردن shell با root
        Shell.getShell(shell -> {
            Log.d("AppBackup", "Shell ready. isRoot=" + shell.isRoot() + " status=" + shell.getStatus());
        });
    }
}
