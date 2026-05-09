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
    private String[] keystoreFiles;

    public BackupMeta() {
    }

    /**
     * تبدیل به JSON برای ذخیره توی فایل
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
        json.put("selinuxContext", selinuxContext);
        json.put("totalSize", totalSize);
        json.put("hasApk", hasApk);
        json.put("hasSplitApks", hasSplitApks);
        json.put("hasInternalData", hasInternalData);
        json.put("hasDeviceProtectedData", hasDeviceProtectedData);
        json.put("hasExternalData", hasExternalData);
        json.put("hasObb", hasObb);
        json.put("hasKeystore", hasKeystore);
        if (keystoreFiles != null) {
            JSONArray arr = new JSONArray();
            for (String f : keystoreFiles) arr.put(f);
            json.put("keystoreFiles", arr);
        }
        return json;
    }

    /**
     * ساخت BackupMeta از یک JSONObject
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
        meta.selinuxContext = json.optString("selinuxContext", "");
        meta.totalSize = json.optLong("totalSize", 0);
        meta.hasApk = json.optBoolean("hasApk", false);
        meta.hasSplitApks = json.optBoolean("hasSplitApks", false);
        meta.hasInternalData = json.optBoolean("hasInternalData", false);
        meta.hasDeviceProtectedData = json.optBoolean("hasDeviceProtectedData", false);
        meta.hasExternalData = json.optBoolean("hasExternalData", false);
        meta.hasObb = json.optBoolean("hasObb", false);
        meta.hasKeystore = json.optBoolean("hasKeystore", false);
        
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
    public void setBackupId(String backupId) { this.backupId = backupId; }

    public String getBackupName() { return backupName; }
    public void setBackupName(String backupName) { this.backupName = backupName; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }

    public long getVersionCode() { return versionCode; }
    public void setVersionCode(long versionCode) { this.versionCode = versionCode; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public int getUid() { return uid; }
    public void setUid(int uid) { this.uid = uid; }

    public String getSelinuxContext() { return selinuxContext; }
    public void setSelinuxContext(String selinuxContext) { this.selinuxContext = selinuxContext; }

    public long getTotalSize() { return totalSize; }
    public void setTotalSize(long totalSize) { this.totalSize = totalSize; }

    public boolean hasApk() { return hasApk; }
    public void setHasApk(boolean hasApk) { this.hasApk = hasApk; }

    public boolean hasSplitApks() { return hasSplitApks; }
    public void setHasSplitApks(boolean hasSplitApks) { this.hasSplitApks = hasSplitApks; }

    public boolean hasInternalData() { return hasInternalData; }
    public void setHasInternalData(boolean hasInternalData) { this.hasInternalData = hasInternalData; }

    public boolean hasDeviceProtectedData() { return hasDeviceProtectedData; }
    public void setHasDeviceProtectedData(boolean hasDeviceProtectedData) { this.hasDeviceProtectedData = hasDeviceProtectedData; }

    public boolean hasExternalData() { return hasExternalData; }
    public void setHasExternalData(boolean hasExternalData) { this.hasExternalData = hasExternalData; }

    public boolean hasObb() { return hasObb; }
    public void setHasObb(boolean hasObb) { this.hasObb = hasObb; }

    public boolean hasKeystore() { return hasKeystore; }
    public void setHasKeystore(boolean hasKeystore) { this.hasKeystore = hasKeystore; }

    public String[] getKeystoreFiles() { return keystoreFiles; }
    public void setKeystoreFiles(String[] keystoreFiles) { this.keystoreFiles = keystoreFiles; }
}
