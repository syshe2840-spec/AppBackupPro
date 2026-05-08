package com.appbackup.pro.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.appbackup.pro.core.RootShell;
import com.appbackup.pro.models.AppInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ابزارهای کمکی برای کار با اپ‌های نصب‌شده
 */
public class AppUtils {
    private static final String TAG = "AppUtils";

    /**
     * گرفتن لیست تمام اپ‌های نصب‌شده روی گوشی
     * @param includeSystem آیا اپ‌های سیستمی هم لیست بشن
     */
    public static List<AppInfo> getInstalledApps(Context context, boolean includeSystem) {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = context.getPackageManager();

        List<ApplicationInfo> appInfos = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo ai : appInfos) {
            try {
                boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                // اگه نمی‌خوایم system apps، رد کن
                if (!includeSystem && isSystem) {
                    continue;
                }

                // خود اپ ما رو رد کن
                if (ai.packageName.equals(context.getPackageName())) {
                    continue;
                }

                AppInfo info = new AppInfo();
                info.setPackageName(ai.packageName);
                info.setAppName(pm.getApplicationLabel(ai).toString());
                info.setIcon(pm.getApplicationIcon(ai));
                info.setSystemApp(isSystem);
                info.setUid(ai.uid);
                info.setApkPath(ai.sourceDir);

                // گرفتن split APKs
                if (ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0) {
                    info.setSplitApkPaths(ai.splitSourceDirs);
                }

                // گرفتن version info
                try {
                    PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                    info.setVersionName(pi.versionName != null ? pi.versionName : "");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        info.setVersionCode(pi.getLongVersionCode());
                    } else {
                        info.setVersionCode(pi.versionCode);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting version for " + ai.packageName);
                }

                apps.add(info);
            } catch (Exception e) {
                Log.e(TAG, "Error processing app: " + ai.packageName, e);
            }
        }

        // مرتب‌سازی بر اساس اسم اپ
        Collections.sort(apps, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return a.getAppName().compareToIgnoreCase(b.getAppName());
            }
        });

        return apps;
    }

    /**
     * چک می‌کنه که آیا اپی با این پکیج روی گوشی نصبه
     */
    public static boolean isAppInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * گرفتن اطلاعات یک اپ خاص
     */
    public static AppInfo getAppInfo(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            PackageInfo pi = pm.getPackageInfo(packageName, 0);

            AppInfo info = new AppInfo();
            info.setPackageName(packageName);
            info.setAppName(pm.getApplicationLabel(ai).toString());
            info.setIcon(pm.getApplicationIcon(ai));
            info.setSystemApp((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            info.setUid(ai.uid);
            info.setApkPath(ai.sourceDir);

            if (ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0) {
                info.setSplitApkPaths(ai.splitSourceDirs);
            }

            info.setVersionName(pi.versionName != null ? pi.versionName : "");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.setVersionCode(pi.getLongVersionCode());
            } else {
                info.setVersionCode(pi.versionCode);
            }
            return info;
        } catch (Exception e) {
            Log.e(TAG, "Error getting app info: " + packageName, e);
            return null;
        }
    }

    /**
     * گرفتن سایز کامل دیتای یک اپ (با root)
     */
    public static long getAppDataSize(String packageName) {
        long total = 0;
        // internal data
        total += RootShell.getDirSize("/data/data/" + packageName);
        // device-protected data
        total += RootShell.getDirSize("/data/user_de/0/" + packageName);
        // external data
        total += RootShell.getDirSize("/sdcard/Android/data/" + packageName);
        // OBB
        total += RootShell.getDirSize("/sdcard/Android/obb/" + packageName);
        return total;
    }
}