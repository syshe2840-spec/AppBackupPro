package com.appbackup.pro;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.appbackup.pro.core.AppWarnings;
import com.appbackup.pro.core.AuthManager;
import com.appbackup.pro.core.BackupRepository;
import com.appbackup.pro.core.BackupVerifier;
import com.appbackup.pro.core.MultiUserHelper;
import com.appbackup.pro.core.RestoreEngine;
import com.appbackup.pro.core.RestoreOptions;
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
        
        // ⭐ محافظت: اگه login نشده، redirect به MainActivity
        if (!AuthManager.getInstance(this).isLoggedIn()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
            return;
        }
        
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

    @Override
    public void onRestoreClick(final File backupDir, final BackupMeta meta) {
        String[] options = {
                "🔄 Full Restore", "🎯 Selective Restore...", "👁 Dry Run",
                "🔍 Verify Backup", "⚙️ Advanced Options..."
        };
        new AlertDialog.Builder(this)
                .setTitle(meta.getBackupName())
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: confirmFullRestore(backupDir, meta); break;
                        case 1: showSelectiveRestoreDialog(backupDir, meta); break;
                        case 2: startRestore(backupDir, meta, new RestoreOptions(RestoreOptions.RestoreMode.FULL), true, false, false); break;
                        case 3: verifyBackup(backupDir, meta); break;
                        case 4: showAdvancedOptionsDialog(backupDir, meta); break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSelectiveRestoreDialog(final File backupDir, final BackupMeta meta) {
        String[] options = {"📦 APK Only", "💾 Data Only", "🗄 Databases", "⚙️ Prefs",
                "📁 Files", "📂 External Data", "🎮 OBB", "🛠 Custom..."};
        new AlertDialog.Builder(this)
                .setTitle("Selective Restore")
                .setItems(options, (d, which) -> {
                    RestoreOptions opts;
                    switch (which) {
                        case 0: opts = new RestoreOptions(RestoreOptions.RestoreMode.APK_ONLY); break;
                        case 1: opts = new RestoreOptions(RestoreOptions.RestoreMode.DATA_ONLY); break;
                        case 2: opts = new RestoreOptions(RestoreOptions.RestoreMode.DATABASES_ONLY); break;
                        case 3: opts = new RestoreOptions(RestoreOptions.RestoreMode.SHARED_PREFS_ONLY); break;
                        case 4: opts = new RestoreOptions(RestoreOptions.RestoreMode.FILES_ONLY); break;
                        case 5: opts = new RestoreOptions(RestoreOptions.RestoreMode.EXTERNAL_DATA_ONLY); break;
                        case 6: opts = new RestoreOptions(RestoreOptions.RestoreMode.OBB_ONLY); break;
                        case 7: showCustomComponentsDialog(backupDir, meta); return;
                        default: return;
                    }
                    confirmSelectiveRestore(backupDir, meta, opts);
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private void showCustomComponentsDialog(final File backupDir, final BackupMeta meta) {
        String[] components = {"APK", "Internal Data", "Databases", "Shared Prefs",
                "Files", "DE Data", "External", "OBB", "Native Libs", "KeyStore", "Permissions"};
        boolean[] checked = {meta.hasApk(), meta.hasInternalData(), meta.hasInternalData(),
                meta.hasInternalData(), meta.hasInternalData(), meta.hasDeviceProtectedData(),
                meta.hasExternalData(), meta.hasObb(), meta.hasNativeLibs(),
                meta.hasKeystore(), meta.hasPermissions()};

        new AlertDialog.Builder(this)
                .setTitle("Custom Restore")
                .setMultiChoiceItems(components, checked, (d, w, isChecked) -> checked[w] = isChecked)
                .setPositiveButton("Restore", (d, w) -> {
                    RestoreOptions opts = new RestoreOptions(RestoreOptions.RestoreMode.CUSTOM);
                    opts.setRestoreApk(checked[0]);
                    opts.setRestoreInternalData(checked[1]);
                    opts.setRestoreDatabases(checked[2]);
                    opts.setRestoreSharedPrefs(checked[3]);
                    opts.setRestoreFiles(checked[4]);
                    opts.setRestoreDeData(checked[5]);
                    opts.setRestoreExternalData(checked[6]);
                    opts.setRestoreObb(checked[7]);
                    opts.setRestoreNativeLibs(checked[8]);
                    opts.setRestoreKeystore(checked[9]);
                    opts.setRestorePermissions(checked[10]);
                    confirmSelectiveRestore(backupDir, meta, opts);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAdvancedOptionsDialog(final File backupDir, final BackupMeta meta) {
        String[] options = {"💪 Force", "⚡ Quick", "👥 To user...", "🛠 Force + Quick + Custom"};
        new AlertDialog.Builder(this)
                .setTitle("Advanced")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: confirmForceRestore(backupDir, meta); break;
                        case 1: startRestore(backupDir, meta, new RestoreOptions(RestoreOptions.RestoreMode.FULL), false, false, true); break;
                        case 2: showUserSelectionDialog(backupDir, meta); break;
                        case 3: showFullCustomDialog(backupDir, meta); break;
                    }
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private void showUserSelectionDialog(final File backupDir, final BackupMeta meta) {
        progressHelper.show("Loading users", "Please wait...");
        new Thread(() -> {
            final List<MultiUserHelper.AndroidUser> users = MultiUserHelper.getAllUsers();
            runOnUiThread(() -> {
                progressHelper.dismiss();
                if (users.size() <= 1) {
                    Toast.makeText(this, "Only main user", Toast.LENGTH_SHORT).show();
                    confirmFullRestore(backupDir, meta);
                    return;
                }
                String[] userNames = new String[users.size()];
                for (int i = 0; i < users.size(); i++) {
                    MultiUserHelper.AndroidUser u = users.get(i);
                    userNames[i] = "User " + u.userId + ": " + u.userName + (u.isMain ? " (Main)" : "");
                }
                new AlertDialog.Builder(this)
                        .setTitle("Select user")
                        .setItems(userNames, (d, which) -> {
                            RestoreOptions opts = new RestoreOptions(RestoreOptions.RestoreMode.FULL);
                            opts.setUserId(users.get(which).userId);
                            startRestore(backupDir, meta, opts, false, false, false);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    private void showFullCustomDialog(final File backupDir, final BackupMeta meta) {
        String[] modes = {"Standard", "Force", "Quick", "Force + Quick"};
        final int[] selected = {0};
        new AlertDialog.Builder(this)
                .setTitle("Mode")
                .setSingleChoiceItems(modes, 0, (d, w) -> selected[0] = w)
                .setPositiveButton("Next", (d, w) -> {
                    boolean force = selected[0] == 1 || selected[0] == 3;
                    boolean quick = selected[0] == 2 || selected[0] == 3;
                    showCustomForExecution(backupDir, meta, force, quick);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomForExecution(final File backupDir, final BackupMeta meta,
                                        final boolean force, final boolean quick) {
        String[] components = {"APK", "Internal", "DE", "External", "OBB", "Native", "KeyStore", "Perms"};
        boolean[] checked = {meta.hasApk(), meta.hasInternalData(), meta.hasDeviceProtectedData(),
                meta.hasExternalData(), meta.hasObb(), meta.hasNativeLibs(),
                meta.hasKeystore(), meta.hasPermissions()};

        new AlertDialog.Builder(this)
                .setTitle("Components")
                .setMultiChoiceItems(components, checked, (d, w, isChecked) -> checked[w] = isChecked)
                .setPositiveButton("Restore", (d, w) -> {
                    RestoreOptions opts = new RestoreOptions(RestoreOptions.RestoreMode.CUSTOM);
                    opts.setRestoreApk(checked[0]);
                    opts.setRestoreInternalData(checked[1]);
                    opts.setRestoreDatabases(checked[1]);
                    opts.setRestoreSharedPrefs(checked[1]);
                    opts.setRestoreFiles(checked[1]);
                    opts.setRestoreDeData(checked[2]);
                    opts.setRestoreExternalData(checked[3]);
                    opts.setRestoreObb(checked[4]);
                    opts.setRestoreNativeLibs(checked[5]);
                    opts.setRestoreKeystore(checked[6]);
                    opts.setRestorePermissions(checked[7]);
                    startRestore(backupDir, meta, opts, false, force, quick);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmFullRestore(final File backupDir, final BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("Full Restore")
                .setMessage("Replace app data?\n\n" + meta.getPackageName())
                .setPositiveButton("Restore", (d, w) -> startRestore(backupDir, meta,
                        new RestoreOptions(RestoreOptions.RestoreMode.FULL), false, false, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmSelectiveRestore(final File backupDir, final BackupMeta meta, final RestoreOptions opts) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setMessage("Mode: " + opts.getDescription())
                .setPositiveButton("Restore", (d, w) -> startRestore(backupDir, meta, opts, false, false, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmForceRestore(File backupDir, BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Force")
                .setMessage("Continue with errors?")
                .setPositiveButton("Force", (d, w) -> startRestore(backupDir, meta,
                        new RestoreOptions(RestoreOptions.RestoreMode.FULL), false, true, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void verifyBackup(File backupDir, BackupMeta meta) {
        progressHelper.show("Verifying", "Please wait...");
        new Thread(() -> {
            final BackupVerifier.VerifyResult result = BackupVerifier.verifyBackup(backupDir, meta);
            runOnUiThread(() -> {
                progressHelper.dismiss();
                String title = result.success ? "✅ Valid" : "❌ Issues";
                TextView tv = new TextView(this);
                tv.setText(result.summary());
                tv.setPadding(40, 20, 40, 20);
                tv.setTextSize(13);
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(tv)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
    }

    @Override
    public void onDeleteClick(final File backupDir, final BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete \"" + meta.getBackupName() + "\"?")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    final boolean ok = repository.deleteBackup(backupDir);
                    runOnUiThread(() -> {
                        Toast.makeText(this, ok ? "Deleted" : "Failed", Toast.LENGTH_SHORT).show();
                        if (ok) loadBackups();
                    });
                }).start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRenameClick(final File backupDir, final BackupMeta meta) {
        final EditText editText = new EditText(this);
        editText.setText(meta.getBackupName());
        editText.setSelection(editText.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(editText)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = editText.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "Empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean ok = repository.renameBackup(backupDir, newName);
                    Toast.makeText(this, ok ? "Renamed" : "Failed", Toast.LENGTH_SHORT).show();
                    if (ok) loadBackups();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startRestore(final File backupDir, final BackupMeta meta,
                              final RestoreOptions options, final boolean dryRun,
                              final boolean forceMode, final boolean skipVerify) {
        progressHelper.show(dryRun ? "Dry Run" : "Restoring", "Starting...");
        new Thread(() -> {
            RestoreEngine engine = new RestoreEngine(BackupListActivity.this)
                    .setOptions(options)
                    .setDryRun(dryRun)
                    .setForceMode(forceMode)
                    .setSkipVerification(skipVerify);
            engine.setProgressCallback((message, percent) -> progressHelper.update(message, percent));
            final BackupResult result = engine.restore(backupDir, meta);
            runOnUiThread(() -> {
                progressHelper.dismiss();
                String title = result.isSuccess() ? "Success ✓" : "Failed ✗";
                TextView tv = new TextView(this);
                tv.setText(result.getMessage());
                tv.setPadding(40, 20, 40, 20);
                tv.setTextSize(13);
                tv.setVerticalScrollBarEnabled(true);
                new AlertDialog.Builder(BackupListActivity.this)
                        .setTitle(title)
                        .setView(tv)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
    }
}
