package com.appbackup.pro;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.appbackup.pro.core.AppWarnings;
import com.appbackup.pro.core.BackupEngine;
import com.appbackup.pro.core.BackupRepository;
import com.appbackup.pro.core.RestoreEngine;
import com.appbackup.pro.core.RootShell;
import com.appbackup.pro.models.AppInfo;
import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.models.BackupResult;
import com.appbackup.pro.ui.AppListAdapter;
import com.appbackup.pro.ui.ProgressDialogHelper;
import com.appbackup.pro.utils.AppUtils;
import com.appbackup.pro.utils.PermissionHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements AppListAdapter.OnAppClickListener {

    private RecyclerView recyclerView;
    private AppListAdapter adapter;
    private TextView tvRootStatus;
    private TextView tvAppCount;
    private EditText etSearch;
    private SwitchCompat switchSystemApps;
    private ProgressBar pbLoading;
    private View btnViewBackups;

    private List<AppInfo> allApps = new ArrayList<>();
    private boolean showSystemApps = true;

    private ProgressDialogHelper progressHelper;
    private BackupRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        progressHelper = new ProgressDialogHelper(this);
        repository = new BackupRepository(this);

        if (!PermissionHelper.hasStoragePermission(this)) {
            PermissionHelper.requestStoragePermission(this);
        }

        checkRootStatus();
        loadApps();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_apps);
        tvRootStatus = findViewById(R.id.tv_root_status);
        tvAppCount = findViewById(R.id.tv_app_count);
        etSearch = findViewById(R.id.et_search);
        switchSystemApps = findViewById(R.id.switch_system_apps);
        pbLoading = findViewById(R.id.pb_loading);
        btnViewBackups = findViewById(R.id.btn_view_backups);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        switchSystemApps.setChecked(showSystemApps);
        switchSystemApps.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            loadApps();
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (adapter != null) {
                    adapter.filter(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnViewBackups.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, BackupListActivity.class);
            startActivity(intent);
        });
    }

    private void checkRootStatus() {
        new Thread(() -> {
            final boolean hasRoot = RootShell.checkRootPermission();
            runOnUiThread(() -> {
                if (hasRoot) {
                    tvRootStatus.setText("✓ Root access granted");
                    tvRootStatus.setTextColor(0xFF2E7D32);
                } else {
                    tvRootStatus.setText("✗ Root access denied");
                    tvRootStatus.setTextColor(0xFFC62828);
                    Toast.makeText(MainActivity.this,
                            "This app requires root access",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void loadApps() {
        pbLoading.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);

        new Thread(() -> {
            final List<AppInfo> apps = AppUtils.getInstalledApps(MainActivity.this, showSystemApps);
            runOnUiThread(() -> {
                allApps = apps;
                if (adapter == null) {
                    adapter = new AppListAdapter(apps, MainActivity.this);
                    recyclerView.setAdapter(adapter);
                } else {
                    adapter.updateData(apps);
                }
                tvAppCount.setText(apps.size() + " apps");
                pbLoading.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    /**
     * ⭐ کلیک Backup - با چک هشدار
     */
    @Override
    public void onBackupClick(AppInfo app) {
        AppWarnings.AppWarning warning = AppWarnings.getWarning(app.getPackageName());
        
        if (warning.level == AppWarnings.WarningLevel.CRITICAL) {
            // اپ‌های CRITICAL: هشدار قوی
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ Backup Warning")
                    .setMessage(warning.message + "\n\nReason: " + warning.reason 
                            + "\n\nDo you still want to backup?")
                    .setPositiveButton("Backup Anyway", (dialog, which) -> showBackupDialog(app))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (warning.level == AppWarnings.WarningLevel.WARNING 
                || warning.level == AppWarnings.WarningLevel.INFO) {
            // اپ‌های WARNING/INFO: هشدار ساده
            new AlertDialog.Builder(this)
                    .setTitle("ℹ️ Note")
                    .setMessage(warning.message + "\n\nProceed with backup?")
                    .setPositiveButton("Backup", (dialog, which) -> showBackupDialog(app))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            // اپ‌های عادی: مستقیم
            showBackupDialog(app);
        }
    }

    private void showBackupDialog(AppInfo app) {
        EditText editText = new EditText(this);
        editText.setHint("Backup name (optional)");
        editText.setText(app.getAppName());

        new AlertDialog.Builder(this)
                .setTitle("Backup " + app.getAppName())
                .setMessage("Create a backup of this app?")
                .setView(editText)
                .setPositiveButton("Backup", (dialog, which) -> {
                    String name = editText.getText().toString().trim();
                    startBackup(app, name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * ⭐ کلیک Restore - با چک هشدار و گزینه‌های پیشرفته
     */
    @Override
    public void onRestoreClick(AppInfo app) {
        List<File> backups = repository.getBackupsForApp(app.getPackageName());
        if (backups.isEmpty()) {
            Toast.makeText(this, "No backups found for this app", Toast.LENGTH_SHORT).show();
            return;
        }

        if (backups.size() == 1) {
            confirmRestore(backups.get(0));
            return;
        }

        String[] options = new String[backups.size()];
        for (int i = 0; i < backups.size(); i++) {
            BackupMeta meta = repository.readMeta(backups.get(i));
            options[i] = meta != null ? meta.getBackupName() + " - " +
                    com.appbackup.pro.utils.FileUtils.formatDate(meta.getCreatedAt())
                    : backups.get(i).getName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Select backup to restore")
                .setItems(options, (dialog, which) -> {
                    confirmRestore(backups.get(which));
                })
                .show();
    }

    /**
     * ⭐ تأیید ریستور با گزینه‌های پیشرفته
     */
    private void confirmRestore(final File backupDir) {
        final BackupMeta meta = repository.readMeta(backupDir);
        if (meta == null) {
            Toast.makeText(this, "Cannot read backup metadata", Toast.LENGTH_SHORT).show();
            return;
        }

        // چک هشدار اپ
        AppWarnings.AppWarning warning = AppWarnings.getWarning(meta.getPackageName());
        String warningText = "";
        if (warning.level != AppWarnings.WarningLevel.NONE) {
            warningText = "\n\n⚠️ " + warning.message;
        }

        String[] options = {
                "🔄 Standard Restore",
                "👁 Dry Run (Preview only)",
                "💪 Force Restore (Ignore errors)",
                "⚡ Quick Restore (Skip verification)"
        };

        new AlertDialog.Builder(this)
                .setTitle("Restore: " + meta.getBackupName() + warningText)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startRestore(backupDir, meta, false, false, false);
                            break;
                        case 1:
                            startRestore(backupDir, meta, true, false, false);
                            break;
                        case 2:
                            confirmForceRestore(backupDir, meta);
                            break;
                        case 3:
                            startRestore(backupDir, meta, false, false, true);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmForceRestore(File backupDir, BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Force Restore")
                .setMessage("Force mode will continue even if errors occur.\n\nThis may leave the app in an inconsistent state.\n\nAre you sure?")
                .setPositiveButton("Force Restore", (dialog, which) -> {
                    startRestore(backupDir, meta, false, true, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startBackup(final AppInfo app, final String name) {
        progressHelper.show("Creating Backup", "Starting...");

        new Thread(() -> {
            BackupEngine engine = new BackupEngine(MainActivity.this);
            engine.setProgressCallback((message, percent) -> {
                progressHelper.update(message, percent);
            });

            final BackupResult result = engine.backup(app, name);

            runOnUiThread(() -> {
                progressHelper.dismiss();
                showResultDialog(result, "Backup");
            });
        }).start();
    }

    /**
     * ⭐ شروع ریستور با گزینه‌های پیشرفته
     */
    private void startRestore(final File backupDir, final BackupMeta meta,
                              final boolean dryRun, final boolean forceMode, 
                              final boolean skipVerify) {
        String title = dryRun ? "Dry Run" : "Restoring Backup";
        progressHelper.show(title, "Starting...");

        new Thread(() -> {
            RestoreEngine engine = new RestoreEngine(MainActivity.this)
                    .setDryRun(dryRun)
                    .setForceMode(forceMode)
                    .setSkipVerification(skipVerify);
                    
            engine.setProgressCallback((message, percent) -> {
                progressHelper.update(message, percent);
            });

            final BackupResult result = engine.restore(backupDir, meta);

            runOnUiThread(() -> {
                progressHelper.dismiss();
                showResultDialog(result, dryRun ? "Dry Run" : "Restore");
                if (result.isSuccess() && !dryRun) {
                    loadApps();
                }
            });
        }).start();
    }

    /**
     * نمایش نتیجه با scrollable text
     */
    private void showResultDialog(BackupResult result, String operation) {
        String title = result.isSuccess() ? operation + " Successful ✓" : operation + " Failed ✗";
        
        // برای text های طولانی، از TextView توی dialog استفاده می‌کنیم
        TextView textView = new TextView(this);
        textView.setText(result.getMessage());
        textView.setPadding(40, 20, 40, 20);
        textView.setTextSize(13);
        textView.setVerticalScrollBarEnabled(true);
        
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(textView)
                .setPositiveButton("OK", null)
                .show();
    }
}
