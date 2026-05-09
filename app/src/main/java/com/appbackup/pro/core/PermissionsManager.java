package com.appbackup.pro.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.appbackup.pro.utils.FileUtils;

/**
 * مدیریت بکاپ و ریستور runtime permissions
 * این برای اپ‌هایی مفیده که نیاز به permissions خاص دارن
 */
public class PermissionsManager {
    private static final String TAG = "AppBackupPro_DEBUG";

    /**
     * بکاپ گرفتن از permissions اپ
     * @return تعداد permission‌های بکاپ شده
     */
    public static int backupPermissions(String packageName, File backupDir) {
        try {
            Log.d(TAG, "─── PERMISSIONS BACKUP ───");
            
            // گرفتن لیست permissions با dumpsys
            RootShell.Result r = RootShell.run("dumpsys package " + packageName 
                + " | grep -A 200 'runtime permissions:' | head -100");
            
            if (!r.success) {
                Log.w(TAG, "Cannot get permissions: " + r.allOutput());
                return 0;
            }
            
            JSONArray perms = new JSONArray();
            String[] lines = r.stdout.split("\n");
            
            for (String line : lines) {
                line = line.trim();
                // فرمت: android.permission.XXX: granted=true, flags=[ ... ]
                if (line.contains("android.permission.") && line.contains(":")) {
                    int colonIdx = line.indexOf(":");
                    String permName = line.substring(0, colonIdx).trim();
                    String permData = line.substring(colonIdx + 1).trim();
                    
                    boolean granted = permData.contains("granted=true");
                    
                    JSONObject perm = new JSONObject();
                    perm.put("name", permName);
                    perm.put("granted", granted);
                    perms.put(perm);
                    
                    Log.d(TAG, "  " + (granted ? "✓" : "✗") + " " + permName);
                }
            }
            
            if (perms.length() > 0) {
                File permsFile = new File(backupDir, "permissions.json");
                FileUtils.writeString(permsFile, perms.toString(2));
                Log.d(TAG, "✓ Permissions backed up: " + perms.length() + " items");
                return perms.length();
            }
            
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Permissions backup error", e);
            return 0;
        }
    }

    /**
     * ریستور permissions
     * @return تعداد permission‌های ریستور شده
     */
    public static int restorePermissions(String packageName, File backupDir) {
        try {
            File permsFile = new File(backupDir, "permissions.json");
            if (!permsFile.exists()) {
                Log.d(TAG, "No permissions file in backup");
                return 0;
            }
            
            Log.d(TAG, "─── PERMISSIONS RESTORE ───");
            
            String content = FileUtils.readString(permsFile);
            JSONArray perms = new JSONArray(content);
            
            int restored = 0;
            for (int i = 0; i < perms.length(); i++) {
                JSONObject perm = perms.getJSONObject(i);
                String name = perm.getString("name");
                boolean granted = perm.getBoolean("granted");
                
                String cmd;
                if (granted) {
                    cmd = "pm grant " + packageName + " " + name;
                } else {
                    cmd = "pm revoke " + packageName + " " + name;
                }
                
                RootShell.Result r = RootShell.run(cmd + " 2>&1");
                if (r.success) {
                    restored++;
                    Log.d(TAG, "  ✓ " + (granted ? "Granted" : "Revoked") + ": " + name);
                } else {
                    // بعضی permissions قابل grant نیستن، طبیعیه
                    Log.d(TAG, "  - Skipped: " + name + " (" + r.stderr.trim() + ")");
                }
            }
            
            Log.d(TAG, "✓ Permissions restored: " + restored + "/" + perms.length());
            return restored;
        } catch (Exception e) {
            Log.e(TAG, "Permissions restore error", e);
            return 0;
        }
    }
}
