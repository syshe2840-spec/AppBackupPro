package com.appbackup.pro;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.appbackup.pro.core.AppWarnings;
import com.appbackup.pro.core.BackupRepository;
import com.appbackup.pro.core.BackupVerifier;
import com.appbackup.pro.core.RestoreEngine;
import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.ui.BackupListAdapter;
import com.appbackup.pro.ui.ProgressDialogHelper;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;
import java.util.List;

public class BackupListActivity extends AppCompatActivity implements BackupListAdapter.OnBackupClickListener {

    private RecyclerView recyclerView;
    private BackupListAdapter adapter;
    private TextView tvBackupCount;
    private TextView tvTotalSize;
    private TextView tvEmpty;

    private BackupRepository repository;
    private ProgressDialogHelper progressHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup_list);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Backups");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        repository = new BackupRepository(this);
        progressHelper = new ProgressDialogHelper(this);

        recyclerView = findViewById(R.id.recycler_backups);
        tvBackupCount = findViewById(R.id.tv_backup_count);
        tvTotalSize = findViewById(R.id.tv_total_size);
        tvEmpty = findViewById(R.id.tv_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        loadBackups();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadBackups() {
        new Thread(() -> {
            final List<File> backups = repository.getAllBackups();
            final long totalSize = repository.getTotalBackupsSize();

            runOnUiThread(() -> {
                if (adapter == null) {
                    adapter = new BackupListAdapter(backups, repository, BackupListActivity.this);
                    recyclerView.setAdapter(adapter);
                } else {
                    adapter.updateData(backups);
                }

                tvBackupCount.setText(backups.size() + " backups");
                tvTotalSize.setText("Total: " + FileUtils.formatSize(totalSize));

                if (backups.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    /**
     * ⭐ Restore با گزینه‌های پیشرفته
     */
    @Override
    public void onRestoreClick(final File backupDir, final BackupMeta meta) {
        AppWarnings.AppWarning warning = AppWarnings.getWarning(meta.getPackageName());
        String warningText = "";
        if (warning.level != AppWarnings.WarningLevel.NONE) {
            warningText = "\n\n⚠️ " + warning.message;
        }

        String[] options = {
                "🔄 Standard Restore",
                "👁 Dry Run (Preview)",
                "🔍 Verify Backup",
                "💪 Force Restore",
                "⚡ Quick Restore"
        };

        new AlertDialog.Builder(this)
                .setTitle(meta.getBackupName() + warningText)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startRestore(backupDir, meta, false, false, false);
                            break;
                        case 1:
                            startRestore(backupDir, meta, true, false, false);
                            break;
                        case 2:
                            verifyBackup(backupDir, meta);
                            break;
                        case 3:
                            confirmForceRestore(backupDir, meta);
                            break;
                        case 4:
                            startRestore(backupDir, meta, false, false, true);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * ⭐ Verify Backup - چک می‌کنه بکاپ سالمه
     */
    private void verifyBackup(File backupDir, BackupMeta meta) {
        progressHelper.show("Verifying Backup", "Please wait...");
        
        new Thread(() -> {
            final BackupVerifier.VerifyResult result = BackupVerifier.verifyBackup(backupDir, meta);
            
            runOnUiThread(() -> {
                progressHelper.dismiss();
                
                String title = result.success ? "✅ Backup is Valid" : "❌ Backup has Issues";
                
                TextView textView = new TextView(this);
                textView.setText(result.summary());
                textView.setPadding(40, 20, 40, 20);
                textView.setTextSize(13);
                textView.setVerticalScrollBarEnabled(true);
                
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(textView)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
    }

    private void confirmForceRestore(File backupDir, BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Force Restore")
                .setMessage("Force mode will continue even if errors occur. This may leave the app in an inconsistent state.\n\nAre you sure?")
                .setPositiveButton("Force Restore", (dialog, which) -> {
                    startRestore(backupDir, meta, false, true, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDeleteClick(final File backupDir, final BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Backup")
                .setMessage("Delete \"" + meta.getBackupName() + "\"? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    new Thread(() -> {
                        final boolean ok = repository.deleteBackup(backupDir);
                        runOnUiThread(() -> {
                            if (ok) {
                                Toast.makeText(this, "Backup deleted", Toast.LENGTH_SHORT).show();
                                loadBackups();
                            } else {
                                Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRenameClick(final File backupDir, final BackupMeta meta) {
        final EditText editText = new EditText(this);
        editText.setText(meta.getBackupName());
        editText.setSelection(editText.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Rename Backup")
                .setView(editText)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String newName = editText.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean ok = repository.renameBackup(backupDir, newName);
                    if (ok) {
                        Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show();
                        loadBackups();
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startRestore(final File backupDir, final BackupMeta meta,
                              final boolean dryRun, final boolean forceMode,
                              final boolean skipVerify) {
        String title = dryRun ? "Dry Run" : "Restoring Backup";
        progressHelper.show(title, "Starting...");

        new Thread(() -> {
            RestoreEngine engine = new RestoreEngine(BackupListActivity.this)
                    .setDryRun(dryRun)
                    .setForceMode(forceMode)
                    .setSkipVerification(skipVerify);
                    
            engine.setProgressCallback((message, percent) -> {
                progressHelper.update(message, percent);
            });

            final BackupResult result = engine.restore(backupDir, meta);

            runOnUiThread(() -> {
                progressHelper.dismiss();
                
                String resultTitle = result.isSuccess() 
                    ? (dryRun ? "Dry Run Complete ✓" : "Restore Successful ✓")
                    : (dryRun ? "Dry Run Failed ✗" : "Restore Failed ✗");
                
                TextView textView = new TextView(this);
                textView.setText(result.getMessage());
                textView.setPadding(40, 20, 40, 20);
                textView.setTextSize(13);
                textView.setVerticalScrollBarEnabled(true);
                
                new AlertDialog.Builder(BackupListActivity.this)
                        .setTitle(resultTitle)
                        .setView(textView)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
    }
}
