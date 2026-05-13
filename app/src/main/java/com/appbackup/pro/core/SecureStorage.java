package com.appbackup.pro.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * ذخیره‌سازی امن با Android Keystore + AES-256
 * - کلید رمزنگاری توی TEE/StrongBox سخت‌افزاری
 * - با root هم نمی‌تونن decrypt کنن
 * - اگه کسی فایل رو دستکاری کنه، corruption detect میشه و reset میشه
 */
public class SecureStorage {
    private static final String TAG = "AppBackupPro_SECURE";
    private static final String FILE_NAME = "secure_auth_v2";
    
    private static SecureStorage instance;
    private SharedPreferences prefs;
    private final Context context;
    
    private SecureStorage(Context context) {
        this.context = context.getApplicationContext();
        initialize();
    }
    
    public static synchronized SecureStorage getInstance(Context context) {
        if (instance == null) {
            instance = new SecureStorage(context);
        }
        return instance;
    }
    
    private void initialize() {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setUserAuthenticationRequired(false)
                    .build();
            
            prefs = EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            Log.d(TAG, "✓ Secure storage initialized");
        } catch (Exception e) {
            Log.e(TAG, "Secure storage init failed - clearing and retrying", e);
            // اگه فایل دستکاری شده، حذفش کن و دوباره بساز
            context.deleteSharedPreferences(FILE_NAME);
            try {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                prefs = EncryptedSharedPreferences.create(
                        context, FILE_NAME, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception ex) {
                Log.e(TAG, "Fatal: cannot initialize secure storage", ex);
                throw new RuntimeException("Secure storage unavailable", ex);
            }
        }
    }
    
    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }
    
    public String getString(String key, String defaultValue) {
        try {
            return prefs.getString(key, defaultValue);
        } catch (Exception e) {
            Log.e(TAG, "Read error for " + key + " - file may be tampered", e);
            clear();
            return defaultValue;
        }
    }
    
    public void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }
    
    public long getLong(String key, long defaultValue) {
        try {
            return prefs.getLong(key, defaultValue);
        } catch (Exception e) {
            Log.e(TAG, "Read error for " + key, e);
            clear();
            return defaultValue;
        }
    }
    
    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }
    
    public void clear() {
        try {
            prefs.edit().clear().apply();
            Log.w(TAG, "Secure storage cleared");
        } catch (Exception e) {
            // اگه clear نشد، فایل رو حذف کن
            context.deleteSharedPreferences(FILE_NAME);
            initialize();
        }
    }
    
    public boolean contains(String key) {
        try {
            return prefs.contains(key);
        } catch (Exception e) {
            return false;
        }
    }
}
