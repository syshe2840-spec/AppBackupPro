package com.appbackup.pro.core;

import android.util.Log;

/**
 * Helper برای کار با دیتابیس‌های SQLite قبل از بکاپ
 * WAL checkpoint می‌زنه تا دیتابیس consistent باشه
 */
public class DatabaseHelper {
    private static final String TAG = "AppBackupPro_DEBUG";

    /**
     * چک‌پوینت WAL برای همه‌ی دیتابیس‌های اپ
     * این تضمین می‌کنه دیتابیس‌ها قبل از بکاپ کامل و consistent باشن
     */
    public static int checkpointAllDatabases(String packageName) {
        Log.d(TAG, "─── DATABASE CHECKPOINT ───");
        
        String dbPath = "/data/data/" + packageName + "/databases";
        
        if (!RootShell.dirExists(dbPath)) {
            Log.d(TAG, "No databases directory");
            return 0;
        }
        
        // پیدا کردن همه‌ی فایل‌های .db (نه -wal، نه -shm، نه -journal)
        RootShell.Result findR = RootShell.run(
            "find " + dbPath + " -maxdepth 2 -type f -name '*.db' 2>/dev/null");
        
        if (!findR.success || findR.stdout.trim().isEmpty()) {
            Log.d(TAG, "No .db files found");
            return 0;
        }
        
        String[] dbFiles = findR.stdout.trim().split("\n");
        int checkpointed = 0;
        
        for (String dbFile : dbFiles) {
            dbFile = dbFile.trim();
            if (dbFile.isEmpty()) continue;
            
            // چک کنیم WAL فایل داره (یعنی WAL mode فعاله)
            String walFile = dbFile + "-wal";
            if (!RootShell.exists(walFile)) {
                Log.d(TAG, "  - " + getFileName(dbFile) + " (no WAL, skipping)");
                continue;
            }
            
            // چک‌پوینت بزن
            String cmd = "sqlite3 " + RootShell.escapePath(dbFile) 
                + " \"PRAGMA wal_checkpoint(TRUNCATE);\" 2>&1";
            RootShell.Result r = RootShell.run(cmd);
            
            if (r.success) {
                Log.d(TAG, "  ✓ " + getFileName(dbFile) + " checkpointed");
                checkpointed++;
            } else {
                Log.w(TAG, "  ✗ " + getFileName(dbFile) + " failed: " + r.stderr.trim());
            }
        }
        
        // برای DE storage هم همین کار رو می‌کنیم
        String deDbPath = "/data/user_de/0/" + packageName + "/databases";
        if (RootShell.dirExists(deDbPath)) {
            RootShell.Result deFindR = RootShell.run(
                "find " + deDbPath + " -maxdepth 2 -type f -name '*.db' 2>/dev/null");
            
            if (deFindR.success && !deFindR.stdout.trim().isEmpty()) {
                for (String dbFile : deFindR.stdout.trim().split("\n")) {
                    dbFile = dbFile.trim();
                    if (dbFile.isEmpty()) continue;
                    
                    if (!RootShell.exists(dbFile + "-wal")) continue;
                    
                    String cmd = "sqlite3 " + RootShell.escapePath(dbFile) 
                        + " \"PRAGMA wal_checkpoint(TRUNCATE);\" 2>&1";
                    RootShell.Result r = RootShell.run(cmd);
                    if (r.success) {
                        Log.d(TAG, "  ✓ DE: " + getFileName(dbFile));
                        checkpointed++;
                    }
                }
            }
        }
        
        Log.d(TAG, "Total checkpointed: " + checkpointed);
        return checkpointed;
    }
    
    private static String getFileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
