package com.appbackup.pro.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ابزارهای کمکی برای کار با فایل‌ها
 */
public class FileUtils {
    private static final String TAG = "FileUtils";
    private static final String BACKUP_FOLDER_NAME = "Backups";

    /**
     * گرفتن مسیر اصلی پوشه‌ی بکاپ‌ها
     * این مسیر مخصوص اپ ماست و scoped storage مشکلی باهاش نداره
     */
    public static File getBackupRootDir(Context context) {
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = context.getFilesDir();
        }
        File backupDir = new File(externalFilesDir, BACKUP_FOLDER_NAME);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        return backupDir;
    }

    /**
     * ساخت اسم پوشه‌ی بکاپ بر اساس تاریخ + اسم اپ
     */
    public static String generateBackupFolderName(String packageName, String backupName) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        String timestamp = sdf.format(new Date());
        String safeName = sanitizeFileName(backupName);
        return packageName + "_" + timestamp + (safeName.isEmpty() ? "" : "_" + safeName);
    }

    /**
     * پاک‌سازی اسم فایل از کاراکترهای ممنوع
     */
    public static String sanitizeFileName(String name) {
        if (name == null) return "";
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100);
        }
        return cleaned;
    }

    /**
     * نوشتن یک رشته توی فایل
     */
    public static void writeString(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    /**
     * خوندن کل محتوای یک فایل به صورت رشته
     */
    public static String readString(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, read, "UTF-8"));
            }
        }
        return sb.toString();
    }

    /**
     * حذف کامل یک پوشه با همه‌ی محتویاتش
     */
    public static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    /**
     * محاسبه‌ی سایز یک پوشه با کل محتویاتش
     */
    public static long getFolderSize(File folder) {
        if (folder == null || !folder.exists()) {
            return 0;
        }
        if (folder.isFile()) {
            return folder.length();
        }
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                size += getFolderSize(f);
            }
        }
        return size;
    }

    /**
     * فرمت کردن سایز برای نمایش (مثلاً 1.5 MB)
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /**
     * فرمت کردن تاریخ برای نمایش
     */
    public static String formatDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    /**
     * ساخت یک پوشه (اگه نباشه)
     */
    public static boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.exists()) return dir.isDirectory();
        return dir.mkdirs();
    }
}