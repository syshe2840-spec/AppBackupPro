package com.appbackup.pro.core;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot قبل از ریستور
 * اگه ریستور خطا بده، می‌تونیم برگردونیم به حالت قبل (rollback)
 */
public class RestoreSnapshot {
    private static final String TAG = "AppBackupPro_DEBUG";
    private static final String SNAPSHOT_BASE = "/data/local/tmp/appbackup_snapshot";

    private final String packageName;
    private final List<SnapshotEntry> entries = new ArrayList<>();
    private boolean active = false;

    private static class SnapshotEntry {
        String originalPath;
        String snapshotPath;
        boolean existed;  // آیا قبل از snapshot، مسیر وجود داشت؟
    }

    public RestoreSnapshot(String packageName) {
        this.packageName = packageName;
    }

    /**
     * گرفتن snapshot از همه‌ی مسیرهای اپ
     */
    public boolean create() {
        try {
            Log.d(TAG, "");
            Log.d(TAG, "─── CREATING SNAPSHOT ───");
            Log.d(TAG, "Package: " + packageName);
            
            // پاک کردن snapshot قبلی (اگه باشه)
            cleanup();
            
            // ساخت پوشه‌ی snapshot
            String snapshotDir = SNAPSHOT_BASE + "/" + packageName.replace('.', '_');
            RootShell.run("mkdir -p " + snapshotDir);
            
            // مسیرهایی که باید snapshot بگیریم
            String[] paths = {
                "/data/data/" + packageName,
                "/data/user_de/0/" + packageName,
                "/sdcard/Android/data/" + packageName,
                "/sdcard/Android/obb/" + packageName
            };
            
            for (String path : paths) {
                SnapshotEntry entry = new SnapshotEntry();
                entry.originalPath = path;
                entry.snapshotPath = snapshotDir + "/" + path.replace('/', '_');
                entry.existed = RootShell.dirExists(path);
                
                if (entry.existed) {
                    // کپی به snapshot
                    String cmd = "cp -rfL " + path + " " + RootShell.escapePath(entry.snapshotPath);
                    RootShell.Result r = RootShell.run(cmd);
                    if (r.success) {
                        Log.d(TAG, "  ✓ Snapshot: " + path);
                    } else {
                        Log.w(TAG, "  ✗ Snapshot failed: " + path + " - " + r.stderr.trim());
                    }
                } else {
                    Log.d(TAG, "  - Skipped (not exists): " + path);
                }
                
                entries.add(entry);
            }
            
            active = true;
            Log.d(TAG, "✓ Snapshot created");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Snapshot creation error", e);
            return false;
        }
    }

    /**
     * Rollback - برگردوندن از snapshot (اگه ریستور خطا بده)
     */
    public boolean rollback() {
        if (!active) {
            Log.w(TAG, "Cannot rollback - no active snapshot");
            return false;
        }
        
        try {
            Log.d(TAG, "");
            Log.d(TAG, "─── ROLLING BACK ───");
            
            for (SnapshotEntry entry : entries) {
                if (entry.existed) {
                    // پاک کردن وضعیت فعلی
                    RootShell.run("rm -rf " + entry.originalPath);
                    
                    // برگردوندن از snapshot
                    String cmd = "cp -rfL " + RootShell.escapePath(entry.snapshotPath) 
                        + " " + entry.originalPath;
                    RootShell.Result r = RootShell.run(cmd);
                    
                    if (r.success) {
                        Log.d(TAG, "  ✓ Restored: " + entry.originalPath);
                    } else {
                        Log.e(TAG, "  ✗ Rollback failed: " + entry.originalPath);
                    }
                } else {
                    // اگه قبلاً وجود نداشت، باید پاک بشه
                    RootShell.run("rm -rf " + entry.originalPath);
                    Log.d(TAG, "  ✓ Removed (didn't exist before): " + entry.originalPath);
                }
            }
            
            // اصلاح ownership و SELinux
            int uid = RootShell.getAppUid(packageName);
            if (uid > 0) {
                RootShell.run("chown -R " + uid + ":" + uid + " /data/data/" + packageName);
                if (RootShell.dirExists("/data/user_de/0/" + packageName)) {
                    RootShell.run("chown -R " + uid + ":" + uid + " /data/user_de/0/" + packageName);
                }
            }
            RootShell.run("restorecon -R /data/data/" + packageName);
            
            cleanup();
            Log.d(TAG, "✓ Rollback complete");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Rollback error", e);
            return false;
        }
    }

    /**
     * Commit - اگه ریستور موفق بود، snapshot رو پاک کن
     */
    public void commit() {
        if (!active) return;
        
        Log.d(TAG, "─── COMMITTING (deleting snapshot) ───");
        cleanup();
        active = false;
        Log.d(TAG, "✓ Snapshot deleted");
    }

    private void cleanup() {
        String snapshotDir = SNAPSHOT_BASE + "/" + packageName.replace('.', '_');
        RootShell.run("rm -rf " + snapshotDir);
    }
}
