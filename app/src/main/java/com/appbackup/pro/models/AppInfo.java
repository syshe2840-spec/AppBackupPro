package com.appbackup.pro.models;

import android.graphics.drawable.Drawable;

/**
 * مدل اطلاعات یک اپ نصب‌شده روی گوشی
 */
public class AppInfo {
    private String packageName;
    private String appName;
    private String versionName;
    private long versionCode;
    private String apkPath;
    private String[] splitApkPaths;
    private int uid;
    private Drawable icon;
    private boolean isSystemApp;
    private long dataSize;
    private boolean isSelected;

    public AppInfo() {
    }

    public AppInfo(String packageName, String appName) {
        this.packageName = packageName;
        this.appName = appName;
    }

    // Getters and Setters
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }

    public long getVersionCode() { return versionCode; }
    public void setVersionCode(long versionCode) { this.versionCode = versionCode; }

    public String getApkPath() { return apkPath; }
    public void setApkPath(String apkPath) { this.apkPath = apkPath; }

    public String[] getSplitApkPaths() { return splitApkPaths; }
    public void setSplitApkPaths(String[] splitApkPaths) { this.splitApkPaths = splitApkPaths; }

    public int getUid() { return uid; }
    public void setUid(int uid) { this.uid = uid; }

    public Drawable getIcon() { return icon; }
    public void setIcon(Drawable icon) { this.icon = icon; }

    public boolean isSystemApp() { return isSystemApp; }
    public void setSystemApp(boolean systemApp) { isSystemApp = systemApp; }

    public long getDataSize() { return dataSize; }
    public void setDataSize(long dataSize) { this.dataSize = dataSize; }

    public boolean isSelected() { return isSelected; }
    public void setSelected(boolean selected) { isSelected = selected; }

    public boolean hasSplits() {
        return splitApkPaths != null && splitApkPaths.length > 0;
    }
}