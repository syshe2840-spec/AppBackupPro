package com.appbackup.pro.core;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * مدیریت چندین user اندروید (Work Profile, Guest, etc.)
 */
public class MultiUserHelper {
    private static final String TAG = "AppBackupPro_DEBUG";

    public static class AndroidUser {
        public int userId;
        public String userName;
        public boolean isMain;
        public boolean isWorkProfile;

        public AndroidUser(int userId, String userName) {
            this.userId = userId;
            this.userName = userName;
            this.isMain = (userId == 0);
        }

        @Override
        public String toString() {
            return "User " + userId + " (" + userName + ")";
        }
    }

    /**
     * گرفتن لیست همه‌ی userهای موجود روی گوشی
     */
    public static List<AndroidUser> getAllUsers() {
        List<AndroidUser> users = new ArrayList<>();
        
        try {
            // pm list users خروجی شبیه این می‌ده:
            // Users:
            //     UserInfo{0:Owner:13} running
            //     UserInfo{10:Work profile:30} running
            RootShell.Result r = RootShell.run("pm list users");
            
            if (!r.success) {
                // fallback به user 0
                users.add(new AndroidUser(0, "Owner"));
                return users;
            }
            
            String[] lines = r.stdout.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.startsWith("UserInfo{")) continue;
                
                try {
                    // فرمت: UserInfo{ID:NAME:FLAGS}
                    int braceStart = line.indexOf('{');
                    int braceEnd = line.indexOf('}');
                    if (braceStart < 0 || braceEnd < 0) continue;
                    
                    String inner = line.substring(braceStart + 1, braceEnd);
                    String[] parts = inner.split(":");
                    if (parts.length < 2) continue;
                    
                    int userId = Integer.parseInt(parts[0]);
                    String userName = parts[1];
                    
                    AndroidUser user = new AndroidUser(userId, userName);
                    
                    // تشخیص work profile (معمولاً flag خاصی دارن)
                    if (parts.length >= 3) {
                        try {
                            int flags = Integer.parseInt(parts[2]);
                            // FLAG_MANAGED_PROFILE = 0x00000020 = 32
                            user.isWorkProfile = (flags & 0x20) != 0;
                        } catch (NumberFormatException e) {}
                    }
                    
                    users.add(user);
                    Log.d(TAG, "Found user: " + user);
                } catch (Exception e) {
                    Log.w(TAG, "Cannot parse user line: " + line);
                }
            }
            
            // اگه هیچی پیدا نشد، فقط user 0 رو اضافه کن
            if (users.isEmpty()) {
                users.add(new AndroidUser(0, "Owner"));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting users", e);
            users.add(new AndroidUser(0, "Owner"));
        }
        
        return users;
    }

    /**
     * گرفتن مسیر data path برای یه user خاص
     */
    public static String getDataPath(String packageName, int userId) {
        if (userId == 0) {
            return "/data/data/" + packageName;
        }
        // برای userهای دیگه: /data/user/X/<pkg>
        return "/data/user/" + userId + "/" + packageName;
    }

    /**
     * گرفتن DE data path برای یه user خاص
     */
    public static String getDeDataPath(String packageName, int userId) {
        return "/data/user_de/" + userId + "/" + packageName;
    }

    /**
     * چک می‌کنه آیا اپ توی این user نصب هست
     */
    public static boolean isAppInstalledForUser(String packageName, int userId) {
        RootShell.Result r = RootShell.run(
            "pm list packages --user " + userId + " | grep -w 'package:" + packageName + "'");
        return r.success && r.stdout.contains(packageName);
    }

    /**
     * گرفتن UID اپ برای یه user خاص
     */
    public static int getAppUidForUser(String packageName, int userId) {
        String path = getDataPath(packageName, userId);
        RootShell.Result r = RootShell.run("stat -c '%u' " + path);
        if (r.success) {
            try {
                return Integer.parseInt(r.stdout.trim());
            } catch (NumberFormatException e) {}
        }
        return -1;
    }
}
