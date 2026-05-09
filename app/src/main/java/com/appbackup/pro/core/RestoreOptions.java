package com.appbackup.pro.core;

/**
 * گزینه‌های انتخابی برای ریستور
 * کاربر می‌تونه انتخاب کنه چی ریستور بشه
 */
public class RestoreOptions {
    
    public enum RestoreMode {
        FULL,                  // همه چی
        APK_ONLY,              // فقط نصب APK
        DATA_ONLY,             // فقط internal data (بدون APK)
        DATABASES_ONLY,        // فقط /databases/
        SHARED_PREFS_ONLY,     // فقط /shared_prefs/
        FILES_ONLY,            // فقط /files/
        EXTERNAL_DATA_ONLY,    // فقط /sdcard/Android/data
        OBB_ONLY,              // فقط /sdcard/Android/obb
        CUSTOM                 // ترکیب آزاد
    }
    
    private RestoreMode mode = RestoreMode.FULL;
    private int userId = 0;  // user اندروید (0 = main, 10 = work profile)
    
    // فلگ‌های CUSTOM mode
    private boolean restoreApk = true;
    private boolean restoreInternalData = true;
    private boolean restoreDatabases = true;
    private boolean restoreSharedPrefs = true;
    private boolean restoreFiles = true;
    private boolean restoreCache = false;  // پیش‌فرض false
    private boolean restoreDeData = true;
    private boolean restoreExternalData = true;
    private boolean restoreObb = true;
    private boolean restoreKeystore = true;
    private boolean restoreNativeLibs = true;
    private boolean restorePermissions = true;

    public RestoreOptions() {
    }

    public RestoreOptions(RestoreMode mode) {
        this.mode = mode;
        applyModePresets();
    }

    /**
     * اعمال preset بر اساس mode
     */
    private void applyModePresets() {
        switch (mode) {
            case FULL:
                restoreApk = true;
                restoreInternalData = true;
                restoreDatabases = true;
                restoreSharedPrefs = true;
                restoreFiles = true;
                restoreDeData = true;
                restoreExternalData = true;
                restoreObb = true;
                restoreKeystore = true;
                restoreNativeLibs = true;
                restorePermissions = true;
                break;
                
            case APK_ONLY:
                restoreApk = true;
                restoreInternalData = false;
                restoreDatabases = false;
                restoreSharedPrefs = false;
                restoreFiles = false;
                restoreDeData = false;
                restoreExternalData = false;
                restoreObb = false;
                restoreKeystore = false;
                restoreNativeLibs = false;
                restorePermissions = false;
                break;
                
            case DATA_ONLY:
                restoreApk = false;
                restoreInternalData = true;
                restoreDatabases = true;
                restoreSharedPrefs = true;
                restoreFiles = true;
                restoreDeData = true;
                restoreExternalData = false;
                restoreObb = false;
                restoreKeystore = true;
                restoreNativeLibs = false;
                restorePermissions = true;
                break;
                
            case DATABASES_ONLY:
                restoreApk = false;
                restoreInternalData = false;  // partial restore
                restoreDatabases = true;
                restoreSharedPrefs = false;
                restoreFiles = false;
                restoreDeData = false;
                restoreExternalData = false;
                restoreObb = false;
                restoreKeystore = false;
                restoreNativeLibs = false;
                restorePermissions = false;
                break;
                
            case SHARED_PREFS_ONLY:
                restoreApk = false;
                restoreInternalData = false;
                restoreDatabases = false;
                restoreSharedPrefs = true;
                restoreFiles = false;
                restoreDeData = false;
                restoreExternalData = false;
                restoreObb = false;
                restoreKeystore = false;
                restoreNativeLibs = false;
                restorePermissions = false;
                break;
                
            case FILES_ONLY:
                restoreApk = false;
                restoreInternalData = false;
                restoreDatabases = false;
                restoreSharedPrefs = false;
                restoreFiles = true;
                restoreDeData = false;
                restoreExternalData = false;
                restoreObb = false;
                restoreKeystore = false;
                restoreNativeLibs = false;
                restorePermissions = false;
                break;
                
            case EXTERNAL_DATA_ONLY:
                restoreApk = false;
                restoreInternalData = false;
                restoreDatabases = false;
                restoreSharedPrefs = false;
                restoreFiles = false;
                restoreDeData = false;
                restoreExternalData = true;
                restoreObb = false;
                restoreKeystore = false;
                restoreNativeLibs = false;
                restorePermissions = false;
                break;
                
            case OBB_ONLY:
                restoreApk = false;
                restoreInternalData = false;
                restoreDatabases = false;
                restoreSharedPrefs = false;
                restoreFiles = false;
                restoreDeData = false;
                restoreExternalData = false;
                restoreObb = true;
                restoreKeystore = false;
                restoreNativeLibs = false;
                restorePermissions = false;
                break;
                
            case CUSTOM:
                // فلگ‌ها دستی تنظیم میشن
                break;
        }
    }

    /**
     * بررسی اینکه آیا این یه partial restore هست (نه FULL)
     */
    public boolean isPartial() {
        return mode != RestoreMode.FULL;
    }

    /**
     * بررسی اینکه آیا فقط بخش خاصی از /data/data ریستور میشه
     */
    public boolean isInternalDataPartial() {
        return mode == RestoreMode.DATABASES_ONLY 
            || mode == RestoreMode.SHARED_PREFS_ONLY 
            || mode == RestoreMode.FILES_ONLY;
    }

    /**
     * گرفتن نام پوشه‌ی هدف برای partial restore
     */
    public String getPartialTargetSubdir() {
        switch (mode) {
            case DATABASES_ONLY: return "databases";
            case SHARED_PREFS_ONLY: return "shared_prefs";
            case FILES_ONLY: return "files";
            default: return null;
        }
    }

    public String getDescription() {
        switch (mode) {
            case FULL: return "Full restore (everything)";
            case APK_ONLY: return "APK only (no data)";
            case DATA_ONLY: return "Data only (no APK install)";
            case DATABASES_ONLY: return "Databases only";
            case SHARED_PREFS_ONLY: return "Shared preferences only";
            case FILES_ONLY: return "Files folder only";
            case EXTERNAL_DATA_ONLY: return "External data only";
            case OBB_ONLY: return "OBB files only";
            case CUSTOM: return "Custom selection";
            default: return "Unknown";
        }
    }

    // Getters and Setters
    public RestoreMode getMode() { return mode; }
    public void setMode(RestoreMode mode) { 
        this.mode = mode; 
        applyModePresets();
    }

    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }

    public boolean isRestoreApk() { return restoreApk; }
    public void setRestoreApk(boolean v) { restoreApk = v; }

    public boolean isRestoreInternalData() { return restoreInternalData; }
    public void setRestoreInternalData(boolean v) { restoreInternalData = v; }

    public boolean isRestoreDatabases() { return restoreDatabases; }
    public void setRestoreDatabases(boolean v) { restoreDatabases = v; }

    public boolean isRestoreSharedPrefs() { return restoreSharedPrefs; }
    public void setRestoreSharedPrefs(boolean v) { restoreSharedPrefs = v; }

    public boolean isRestoreFiles() { return restoreFiles; }
    public void setRestoreFiles(boolean v) { restoreFiles = v; }

    public boolean isRestoreCache() { return restoreCache; }
    public void setRestoreCache(boolean v) { restoreCache = v; }

    public boolean isRestoreDeData() { return restoreDeData; }
    public void setRestoreDeData(boolean v) { restoreDeData = v; }

    public boolean isRestoreExternalData() { return restoreExternalData; }
    public void setRestoreExternalData(boolean v) { restoreExternalData = v; }

    public boolean isRestoreObb() { return restoreObb; }
    public void setRestoreObb(boolean v) { restoreObb = v; }

    public boolean isRestoreKeystore() { return restoreKeystore; }
    public void setRestoreKeystore(boolean v) { restoreKeystore = v; }

    public boolean isRestoreNativeLibs() { return restoreNativeLibs; }
    public void setRestoreNativeLibs(boolean v) { restoreNativeLibs = v; }

    public boolean isRestorePermissions() { return restorePermissions; }
    public void setRestorePermissions(boolean v) { restorePermissions = v; }
}
