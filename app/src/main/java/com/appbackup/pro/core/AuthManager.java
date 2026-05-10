package com.appbackup.pro.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * مدیریت احراز هویت با Cloudflare Worker + D1
 * AES-GCM + HMAC + Periodic check + Device binding
 */
public class AuthManager {
    private static final String TAG = "AppBackupPro_AUTH";
    
    // ⚠️ ⚠️ ⚠️ این دو خط رو با مقادیر خودت عوض کن ⚠️ ⚠️ ⚠️
    private static final String SERVER_URL = "https://apppro.lastofanarchy.workers.dev";
    private static final String AES_KEY_HEX = "98604998169f24a050863d87af78d23fdec2b4fb1bdf51b60fb0bd890f9701be";
    
    private static final String PREFS_NAME = "auth_prefs_v1";
    private static final String KEY_LICENSE = "license_key";
    private static final String KEY_TOKEN_PAYLOAD = "token_payload";
    private static final String KEY_TOKEN_SIG = "token_sig";
    private static final String KEY_LAST_CHECK = "last_check";
    
    private static final long CHECK_INTERVAL_MS = 15 * 60 * 1000;
    
    private static AuthManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final AtomicBoolean isChecking = new AtomicBoolean(false);
    
    public interface AuthCallback {
        void onSuccess();
        void onFailure(String error);
    }
    
    private AuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new AuthManager(context);
        }
        return instance;
    }
    
    /**
     * چک می‌کنه آیا کاربر login شده و token محلی هنوز معتبره
     */
    public boolean isLoggedIn() {
        String license = prefs.getString(KEY_LICENSE, null);
        String tokenPayload = prefs.getString(KEY_TOKEN_PAYLOAD, null);
        
        if (license == null || tokenPayload == null) return false;
        
        try {
            JSONObject payload = new JSONObject(tokenPayload);
            long expires = payload.optLong("expires", 0);
            return System.currentTimeMillis() < expires;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Login با license key
     */
    public void login(final String licenseKey, final AuthCallback callback) {
        new Thread(() -> {
            try {
                JSONObject reqData = new JSONObject();
                reqData.put("license", licenseKey);
                reqData.put("android_id", getAndroidId());
                reqData.put("device_model", Build.MODEL);
                reqData.put("device_manufacturer", Build.MANUFACTURER);
                reqData.put("android_version", "Android " + Build.VERSION.RELEASE 
                    + " (SDK " + Build.VERSION.SDK_INT + ")");
                reqData.put("timestamp", System.currentTimeMillis());
                
                String encrypted = aesEncrypt(reqData.toString());
                
                JSONObject reqBody = new JSONObject();
                reqBody.put("data", encrypted);
                
                String response = httpPost(SERVER_URL + "/verify", reqBody.toString());
                if (response == null) {
                    callback.onFailure("Cannot connect to server. Check internet.");
                    return;
                }
                
                JSONObject respJson = new JSONObject(response);
                
                if (respJson.has("error")) {
                    callback.onFailure(respJson.getString("error"));
                    return;
                }
                
                String encryptedResp = respJson.optString("data", null);
                if (encryptedResp == null) {
                    callback.onFailure("Invalid server response");
                    return;
                }
                
                String decryptedResp = aesDecrypt(encryptedResp);
                if (decryptedResp == null) {
                    callback.onFailure("Cannot decrypt response");
                    return;
                }
                
                JSONObject data = new JSONObject(decryptedResp);
                if (!data.optBoolean("ok", false)) {
                    callback.onFailure(data.optString("error", "Login failed"));
                    return;
                }
                
                JSONObject token = data.getJSONObject("token");
                prefs.edit()
                    .putString(KEY_LICENSE, licenseKey)
                    .putString(KEY_TOKEN_PAYLOAD, token.getString("payload"))
                    .putString(KEY_TOKEN_SIG, token.getString("signature"))
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .apply();
                
                Log.d(TAG, "✓ Login successful");
                callback.onSuccess();
                
            } catch (Exception e) {
                Log.e(TAG, "Login error", e);
                callback.onFailure("Error: " + e.getMessage());
            }
        }).start();
    }
    
    /**
     * Periodic check - هر ۱۵ دقیقه
     */
    public void checkAuth(final AuthCallback callback) {
        if (!isChecking.compareAndSet(false, true)) return;
        
        new Thread(() -> {
            try {
                String license = prefs.getString(KEY_LICENSE, null);
                String tokenPayload = prefs.getString(KEY_TOKEN_PAYLOAD, null);
                String tokenSig = prefs.getString(KEY_TOKEN_SIG, null);
                
                if (license == null || tokenPayload == null) {
                    callback.onFailure("Not logged in");
                    return;
                }
                
                JSONObject token = new JSONObject();
                token.put("payload", tokenPayload);
                token.put("signature", tokenSig);
                
                JSONObject reqData = new JSONObject();
                reqData.put("token", token);
                reqData.put("license", license);
                reqData.put("android_id", getAndroidId());
                reqData.put("device_model", Build.MODEL);
                
                String encrypted = aesEncrypt(reqData.toString());
                
                JSONObject reqBody = new JSONObject();
                reqBody.put("data", encrypted);
                
                String response = httpPost(SERVER_URL + "/check", reqBody.toString());
                if (response == null) {
                    // اینترنت قطعه - token محلی رو بپذیر اگه valid باشه
                    if (isLoggedIn()) {
                        Log.w(TAG, "Network down, using local token");
                        callback.onSuccess();
                    } else {
                        callback.onFailure("Cannot reach server");
                    }
                    return;
                }
                
                JSONObject respJson = new JSONObject(response);
                
                if (respJson.has("error")) {
                    callback.onFailure(respJson.getString("error"));
                    return;
                }
                
                String decryptedResp = aesDecrypt(respJson.getString("data"));
                if (decryptedResp == null) {
                    callback.onFailure("Invalid response");
                    return;
                }
                
                JSONObject data = new JSONObject(decryptedResp);
                if (!data.optBoolean("ok", false)) {
                    Log.w(TAG, "Auth check failed: " + data.optString("error"));
                    logout();
                    callback.onFailure(data.optString("error", "Auth failed"));
                    return;
                }
                
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                Log.d(TAG, "✓ Auth check passed");
                callback.onSuccess();
                
            } catch (Exception e) {
                Log.e(TAG, "Check error", e);
                callback.onFailure("Error: " + e.getMessage());
            } finally {
                isChecking.set(false);
            }
        }).start();
    }
    
    public boolean shouldCheck() {
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        return System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS;
    }
    
    public void logout() {
        prefs.edit().clear().apply();
        Log.d(TAG, "Logged out");
    }
    
    public String getCurrentLicense() {
        return prefs.getString(KEY_LICENSE, null);
    }
    
    // ─── AES-GCM Encryption ───
    
    private String aesEncrypt(String plaintext) throws Exception {
        byte[] keyBytes = hexToBytes(AES_KEY_HEX);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));
        
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" 
             + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }
    
    private String aesDecrypt(String encrypted) {
        try {
            String[] parts = encrypted.split(":");
            if (parts.length != 2) return null;
            
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            
            byte[] keyBytes = hexToBytes(AES_KEY_HEX);
            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Decrypt error: " + e.getMessage());
            return null;
        }
    }
    
    private byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
    
    // ─── HTTP & Device ───
    
    private String httpPost(String urlStr, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "AppBackupPro/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            
            byte[] bodyBytes = body.getBytes("UTF-8");
            conn.getOutputStream().write(bodyBytes);
            
            int code = conn.getResponseCode();
            
            java.io.InputStream is = (code == 200) 
                ? conn.getInputStream() 
                : conn.getErrorStream();
            
            if (is == null) return null;
            
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "HTTP error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    
    @SuppressWarnings("HardwareIds")
    private String getAndroidId() {
        try {
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id != null ? id : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
