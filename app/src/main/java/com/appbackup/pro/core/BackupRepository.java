package com.appbackup.pro.core;

import android.content.Context;
import android.util.Log;

import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.utils.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * مدیریت لیست بکاپ‌ها - خوندن، حذف، تغییر نام
 */
public class BackupRepository {
    private static final String TAG = "BackupRepository";

    private final Context context;

    public BackupRepository(Context context) {
        this.context = context;
    }

    /**
     * گرفتن لیست همه‌ی بکاپ‌ها (مرتب‌شده بر اساس تاریخ - جدیدترین اول)
     */
    public List<File> getAllBackups() {
        List<File> backups = new ArrayList<>();
        File rootDir = FileUtils.getBackupRootDir(context);
        if (!rootDir.exists()) {
            return backups;
        }

        File[] dirs = rootDir.listFiles();
        if (dirs == null) {
            return backups;
        }

        for (File dir : dirs) {
            if (dir.isDirectory()) {
                File metaFile = new File(dir, "metadata.json");
                if (metaFile.exists()) {
                    backups.add(dir);
                }
            }
        }

        // مرتب‌سازی بر اساس تاریخ تغییر - جدیدترین اول
        Collections.sort(backups, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        return backups;
    }

    /**
     * گرفتن بکاپ‌های یک اپ خاص
     */
    public List<File> getBackupsForApp(String packageName) {
        List<File> result = new ArrayList<>();
        for (File backup : getAllBackups()) {
            BackupMeta meta = readMeta(backup);
            if (meta != null && packageName.equals(meta.getPackageName())) {
                result.add(backup);
            }
        }
        return result;
    }

    /**
     * خوندن metadata یک بکاپ
     */
    public BackupMeta readMeta(File backupDir) {
        try {
            File metaFile = new File(backupDir, "metadata.json");
            if (!metaFile.exists()) {
                return null;
            }
            String content = FileUtils.readString(metaFile);
            JSONObject json = new JSONObject(content);
            return BackupMeta.fromJson(json);
        } catch (Exception e) {
            Log.e(TAG, "Error reading meta from: " + backupDir, e);
            return null;
        }
    }

    /**
     * نوشتن metadata روی بکاپ
     */
    public boolean writeMeta(File backupDir, BackupMeta meta) {
        try {
            File metaFile = new File(backupDir, "metadata.json");
            FileUtils.writeString(metaFile, meta.toJson().toString(2));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error writing meta", e);
            return false;
        }
    }

    /**
     * حذف یک بکاپ (با root برای اطمینان)
     */
    public boolean deleteBackup(File backupDir) {
        if (backupDir == null || !backupDir.exists()) {
            return false;
        }
        try {
            // اول با Java معمولی امتحان می‌کنیم
            boolean ok = FileUtils.deleteRecursive(backupDir);
            if (!ok && backupDir.exists()) {
                // اگه نشد، با root حذف می‌کنیم
                String cmd = "rm -rf " + RootShell.escapePath(backupDir.getAbsolutePath());
                RootShell.Result r = RootShell.run(cmd);
                ok = r.success && !backupDir.exists();
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "Delete failed", e);
            return false;
        }
    }

    /**
     * تغییر نام یک بکاپ (فقط backupName داخل metadata - پوشه ثابت می‌مونه)
     */
    public boolean renameBackup(File backupDir, String newName) {
        try {
            BackupMeta meta = readMeta(backupDir);
            if (meta == null) {
                return false;
            }
            meta.setBackupName(newName);
            return writeMeta(backupDir, meta);
        } catch (Exception e) {
            Log.e(TAG, "Rename failed", e);
            return false;
        }
    }

    /**
     * گرفتن سایز کل همه‌ی بکاپ‌ها
     */
    public long getTotalBackupsSize() {
        long total = 0;
        for (File backup : getAllBackups()) {
            total += FileUtils.getFolderSize(backup);
        }
        return total;
    }

    /**
     * گرفتن تعداد کل بکاپ‌ها
     */
    public int getBackupsCount() {
        return getAllBackups().size();
    }
}