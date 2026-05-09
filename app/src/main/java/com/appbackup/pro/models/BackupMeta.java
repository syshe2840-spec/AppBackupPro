package com.appbackup.pro.models;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Metadata کامل هر بکاپ که توی فایل metadata.json ذخیره میشه
 */
public class BackupMeta {
    private String backupId;
    private String backupName;
    private String packageName;
    private String appName;
    private String versionName;
    private long versionCode;
    private long createdAt;
    private int uid;
    private int gid;
    private String selinuxContext;
    private long totalSize;
    
    // پرچم‌هایی که نشون می‌ده چی توی بکاپ هست
    private boolean hasApk;
    private boolean hasSplitApks;
    private boolean hasInternalData;
    private boolean hasDeviceProtectedData;
    private boolean hasExternalData;
    private boolean hasObb;
    private boolean hasKeystore;
    private boolean hasNativeLibs;
    private boolean hasPermissions;
    private String[] keystoreFiles;
    private int permissionsCount;
    private int androidVersionAtBackup;

    public BackupMeta() {
    }

    /**
     * تبدیل به JSON
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("backupId", backupId);
        json.put("backupName", backupName);
        json.put("packageName", packageName);
        json.put("appName", appName);
        json.put("versionName", versionName);
        json.put("versionCode", versionCode);
        json.put("createdAt", createdAt);
        json.put("uid", uid);
        json.put("gid", gid);
        json.put("selinuxContext", selinuxContext);
        json.put("totalSize", totalSize);
        json.put("hasApk", hasApk);
        json.put("hasSplitApks", hasSplitApks);
        json.put("hasInternalData", hasInternalData);
        json.put("hasDeviceProtectedData", hasDeviceProtectedData);
        json.put("hasExternalData", hasExternalData);
        json.put("hasObb", hasObb);
        json.put("hasKeystore", hasKeystore);
        json.put("hasNativeLibs", hasNativeLibs);
        json.put("hasPermissions", hasPermissions);
        json.put("permissionsCount", permissionsCount);
        json.put("androidVersionAtBackup", androidVersionAtBackup);
        if (keystoreFiles != null) {
            JSONArray arr = new JSONArray();
            for (String f : keystoreFiles) arr.put(f);
            json.put("keystoreFiles", arr);
        }
        return json;
    }

    /**
     * ساخت BackupMeta از JSONObject
     */
    public static BackupMeta fromJson(JSONObject json) throws JSONException {
        BackupMeta meta = new BackupMeta();
        meta.backupId = json.optString("backupId", "");
        meta.backupName = json.optString("backupName", "");
        meta.packageName = json.optString("packageName", "");
        meta.appName = json.optString("appName", "");
        meta.versionName = json.optString("versionName", "");
        meta.versionCode = json.optLong("versionCode", 0);
        meta.createdAt = json.optLong("createdAt", 0);
        meta.uid = json.optInt("uid", -1);
        meta.gid = json.optInt("gid", -1);
        meta.selinuxContext = json.optString("selinuxContext", "");
        meta.totalSize = json.optLong("totalSize", 0);
        meta.hasApk = json.optBoolean("hasApk", false);
        meta.hasSplitApks = json.optBoolean("hasSplitApks", false);
        meta.hasInternalData = json.optBoolean("hasInternalData", false);
        meta.hasDeviceProtectedData = json.optBoolean("hasDeviceProtectedData", false);
        meta.hasExternalData = json.optBoolean("hasExternalData", false);
        meta.hasObb = json.optBoolean("hasObb", false);
        meta.hasKeystore = json.optBoolean("hasKeystore", false);
        meta.hasNativeLibs = json.optBoolean("hasNativeLibs", false);
        meta.hasPermissions = json.optBoolean("hasPermissions", false);
        meta.permissionsCount = json.optInt("permissionsCount", 0);
        meta.androidVersionAtBackup = json.optInt("androidVersionAtBackup", 0);
        
        JSONArray arr = json.optJSONArray("keystoreFiles");
        if (arr != null) {
            String[] files = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                files[i] = arr.optString(i);
            }
            meta.keystoreFiles = files;
        }
        
        return meta;
    }

    // Getters and Setters
    public String getBackupId() { return backupId; }
    public void setBackupId(String v) { this.backupId = v; }

    public String getBackupName() { return backupName; }
    public void setBackupName(String v) { this.backupName = v; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String v) { this.packageName = v; }

    public String getAppName() { return appName; }
    public void setAppName(String v) { this.appName = v; }

    public String getVersionName() { return versionName; }
    public void setVersionName(String v) { this.versionName = v; }

    public long getVersionCode() { return versionCode; }
    public void setVersionCode(long v) { this.versionCode = v; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }

    public int getUid() { return uid; }
    public void setUid(int v) { this.uid = v; }

    public int getGid() { return gid; }
    public void setGid(int v) { this.gid = v; }

    public String getSelinuxContext() { return selinuxContext; }
    public void setSelinuxContext(String v) { this.selinuxContext = v; }

    public long getTotalSize() { return totalSize; }
    public void setTotalSize(long v) { this.totalSize = v; }

    public boolean hasApk() { return hasApk; }
    public void setHasApk(boolean v) { this.hasApk = v; }

    public boolean hasSplitApks() { return hasSplitApks; }
    public void setHasSplitApks(boolean v) { this.hasSplitApks = v; }

    public boolean hasInternalData() { return hasInternalData; }
    public void setHasInternalData(boolean v) { this.hasInternalData = v; }

    public boolean hasDeviceProtectedData() { return hasDeviceProtectedData; }
    public void setHasDeviceProtectedData(boolean v) { this.hasDeviceProtectedData = v; }

    public boolean hasExternalData() { return hasExternalData; }
    public void setHasExternalData(boolean v) { this.hasExternalData = v; }

    public boolean hasObb() { return hasObb; }
    public void setHasObb(boolean v) { this.hasObb = v; }

    public boolean hasKeystore() { return hasKeystore; }
    public void setHasKeystore(boolean v) { this.hasKeystore = v; }

    public boolean hasNativeLibs() { return hasNativeLibs; }
    public void setHasNativeLibs(boolean v) { this.hasNativeLibs = v; }

    public boolean hasPermissions() { return hasPermissions; }
    public void setHasPermissions(boolean v) { this.hasPermissions = v; }

    public String[] getKeystoreFiles() { return keystoreFiles; }
    public void setKeystoreFiles(String[] v) { this.keystoreFiles = v; }

    public int getPermissionsCount() { return permissionsCount; }
    public void setPermissionsCount(int v) { this.permissionsCount = v; }

    public int getAndroidVersionAtBackup() { return androidVersionAtBackup; }
    public void setAndroidVersionAtBackup(int v) { this.androidVersionAtBackup = v; }
}
