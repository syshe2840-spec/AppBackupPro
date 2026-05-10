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
import com.appbackup.pro.core.MultiUserHelper;
import com.appbackup.pro.core.RestoreEngine;
import com.appbackup.pro.core.RestoreOptions;
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
     * کلیک Backup - با چک هشدار
     */
    @Override
    public void onBackupClick(AppInfo app) {
        AppWarnings.AppWarning warning = AppWarnings.getWarning(app.getPackageName());
        
        if (warning.level == AppWarnings.WarningLevel.CRITICAL) {
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ Backup Warning")
                    .setMessage(warning.message + "\n\nReason: " + warning.reason 
                            + "\n\nDo you still want to backup?")
                    .setPositiveButton("Backup Anyway", (dialog, which) -> showBackupDialog(app))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (warning.level == AppWarnings.WarningLevel.WARNING 
                || warning.level == AppWarnings.WarningLevel.INFO) {
            new AlertDialog.Builder(this)
                    .setTitle("ℹ️ Note")
                    .setMessage(warning.message + "\n\nProceed with backup?")
                    .setPositiveButton("Backup", (dialog, which) -> showBackupDialog(app))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
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
     * کلیک Restore
     */
    @Override
    public void onRestoreClick(AppInfo app) {
        List<File> backups = repository.getBackupsForApp(app.getPackageName());
        if (backups.isEmpty()) {
            Toast.makeText(this, "No backups found for this app", Toast.LENGTH_SHORT).show();
            return;
        }

        if (backups.size() == 1) {
            showRestoreModeDialog(backups.get(0));
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
                    showRestoreModeDialog(backups.get(which));
                })
                .show();
    }

    /**
     * ⭐ منوی اول: انتخاب mode (Standard / Selective / Advanced)
     */
    private void showRestoreModeDialog(final File backupDir) {
        final BackupMeta meta = repository.readMeta(backupDir);
        if (meta == null) {
            Toast.makeText(this, "Cannot read backup metadata", Toast.LENGTH_SHORT).show();
            return;
        }

        AppWarnings.AppWarning warning = AppWarnings.getWarning(meta.getPackageName());
        String warningText = "";
        if (warning.level != AppWarnings.WarningLevel.NONE) {
            warningText = "\n\n⚠️ " + warning.message;
        }

        String[] options = {
                "🔄 Full Restore",
                "🎯 Selective Restore...",
                "👁 Dry Run (Preview)",
                "🔍 Verify Backup",
                "⚙️ Advanced Options..."
        };

        new AlertDialog.Builder(this)
                .setTitle("Restore: " + meta.getBackupName() + warningText)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // Full
                            confirmFullRestore(backupDir, meta);
                            break;
                        case 1: // Selective
                            showSelectiveRestoreDialog(backupDir, meta);
                            break;
                        case 2: // Dry Run
                            startRestore(backupDir, meta, 
                                new RestoreOptions(RestoreOptions.RestoreMode.FULL),
                                true, false, false);
                            break;
                        case 3: // Verify
                            verifyBackup(backupDir, meta);
                            break;
                        case 4: // Advanced
                            showAdvancedOptionsDialog(backupDir, meta);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * ⭐ منوی Selective Restore
     */
    private void showSelectiveRestoreDialog(final File backupDir, final BackupMeta meta) {
        String[] options = {
                "📦 APK Only (no data)",
                "💾 Data Only (no APK install)",
                "🗄 Databases Only",
                "⚙️ Shared Preferences Only",
                "📁 Files Folder Only",
                "📂 External Data Only",
                "🎮 OBB Files Only",
                "🛠 Custom (choose components...)"
        };

        new AlertDialog.Builder(this)
                .setTitle("Selective Restore")
                .setItems(options, (dialog, which) -> {
                    RestoreOptions opts;
                    switch (which) {
                        case 0: opts = new RestoreOptions(RestoreOptions.RestoreMode.APK_ONLY); break;
                        case 1: opts = new RestoreOptions(RestoreOptions.RestoreMode.DATA_ONLY); break;
                        case 2: opts = new RestoreOptions(RestoreOptions.RestoreMode.DATABASES_ONLY); break;
                        case 3: opts = new RestoreOptions(RestoreOptions.RestoreMode.SHARED_PREFS_ONLY); break;
                        case 4: opts = new RestoreOptions(RestoreOptions.RestoreMode.FILES_ONLY); break;
                        case 5: opts = new RestoreOptions(RestoreOptions.RestoreMode.EXTERNAL_DATA_ONLY); break;
                        case 6: opts = new RestoreOptions(RestoreOptions.RestoreMode.OBB_ONLY); break;
                        case 7: 
                            showCustomComponentsDialog(backupDir, meta);
                            return;
                        default:
                            return;
                    }
                    confirmSelectiveRestore(backupDir, meta, opts);
                })
                .setNegativeButton("Back", (dialog, which) -> showRestoreModeDialog(backupDir))
                .show();
    }

    /**
     * ⭐ Custom Components Dialog (multi-select)
     */
    private void showCustomComponentsDialog(final File backupDir, final BackupMeta meta) {
        String[] components = {
                "Install APK",
                "Internal Data",
                "Databases",
                "Shared Preferences",
                "Files",
                "Device-Protected Data",
                "External Data",
                "OBB Files",
                "Native Libraries",
                "KeyStore",
                "Permissions"
        };
        
        boolean[] checked = {
                meta.hasApk(),
                meta.hasInternalData(),
                meta.hasInternalData(),
                meta.hasInternalData(),
                meta.hasInternalData(),
                meta.hasDeviceProtectedData(),
                meta.hasExternalData(),
                meta.hasObb(),
                meta.hasNativeLibs(),
                meta.hasKeystore(),
                meta.hasPermissions()
        };

        new AlertDialog.Builder(this)
                .setTitle("Custom Restore - Select Components")
                .setMultiChoiceItems(components, checked, (dialog, which, isChecked) -> {
                    checked[which] = isChecked;
                })
                .setPositiveButton("Restore", (dialog, which) -> {
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

    /**
     * ⭐ منوی Advanced Options (Force / Quick / User selection)
     */
    private void showAdvancedOptionsDialog(final File backupDir, final BackupMeta meta) {
        String[] options = {
                "💪 Force Restore (ignore errors)",
                "⚡ Quick Restore (skip verification)",
                "👥 Restore to specific user...",
                "🛠 Force + Quick + Custom..."
        };

        new AlertDialog.Builder(this)
                .setTitle("Advanced Restore Options")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            confirmForceRestore(backupDir, meta);
                            break;
                        case 1:
                            startRestore(backupDir, meta,
                                new RestoreOptions(RestoreOptions.RestoreMode.FULL),
                                false, false, true);
                            break;
                        case 2:
                            showUserSelectionDialog(backupDir, meta);
                            break;
                        case 3:
                            showFullCustomDialog(backupDir, meta);
                            break;
                    }
                })
                .setNegativeButton("Back", (dialog, which) -> showRestoreModeDialog(backupDir))
                .show();
    }

    /**
     * ⭐ انتخاب User (برای Multi-User)
     */
    private void showUserSelectionDialog(final File backupDir, final BackupMeta meta) {
        progressHelper.show("Loading users", "Please wait...");
        
        new Thread(() -> {
            final List<MultiUserHelper.AndroidUser> users = MultiUserHelper.getAllUsers();
            
            runOnUiThread(() -> {
                progressHelper.dismiss();
                
                if (users.size() <= 1) {
                    Toast.makeText(this, "Only main user available", Toast.LENGTH_SHORT).show();
                    confirmFullRestore(backupDir, meta);
                    return;
                }
                
                String[] userNames = new String[users.size()];
                for (int i = 0; i < users.size(); i++) {
                    MultiUserHelper.AndroidUser u = users.get(i);
                    String label = "User " + u.userId + ": " + u.userName;
                    if (u.isMain) label += " (Main)";
                    if (u.isWorkProfile) label += " (Work Profile)";
                    userNames[i] = label;
                }
                
                new AlertDialog.Builder(this)
                        .setTitle("Restore to which user?")
                        .setItems(userNames, (dialog, which) -> {
                            RestoreOptions opts = new RestoreOptions(RestoreOptions.RestoreMode.FULL);
                            opts.setUserId(users.get(which).userId);
                            startRestore(backupDir, meta, opts, false, false, false);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    /**
     * ⭐ Full Custom (همه چی قابل تنظیم)
     */
    private void showFullCustomDialog(final File backupDir, final BackupMeta meta) {
        String[] modeOptions = {"Standard", "Force", "Quick", "Force + Quick"};
        final int[] selectedMode = {0};
        
        new AlertDialog.Builder(this)
                .setTitle("Choose execution mode")
                .setSingleChoiceItems(modeOptions, 0, (dialog, which) -> {
                    selectedMode[0] = which;
                })
                .setPositiveButton("Next", (dialog, which) -> {
                    final boolean force = selectedMode[0] == 1 || selectedMode[0] == 3;
                    final boolean quick = selectedMode[0] == 2 || selectedMode[0] == 3;
                    
                    // بعدش components
                    showCustomComponentsForExecution(backupDir, meta, force, quick);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomComponentsForExecution(final File backupDir, final BackupMeta meta,
                                                  final boolean force, final boolean quick) {
        String[] components = {
                "Install APK",
                "Internal Data",
                "DE Data",
                "External Data",
                "OBB",
                "Native Libs",
                "KeyStore",
                "Permissions"
        };
        
        boolean[] checked = {
                meta.hasApk(),
                meta.hasInternalData(),
                meta.hasDeviceProtectedData(),
                meta.hasExternalData(),
                meta.hasObb(),
                meta.hasNativeLibs(),
                meta.hasKeystore(),
                meta.hasPermissions()
        };

        new AlertDialog.Builder(this)
                .setTitle("Select components to restore")
                .setMultiChoiceItems(components, checked, (dialog, which, isChecked) -> {
                    checked[which] = isChecked;
                })
                .setPositiveButton("Restore", (dialog, which) -> {
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
                .setMessage("This will replace current app data with backup.\n\nContinue?")
                .setPositiveButton("Restore", (dialog, which) -> {
                    startRestore(backupDir, meta, 
                        new RestoreOptions(RestoreOptions.RestoreMode.FULL),
                        false, false, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmSelectiveRestore(final File backupDir, final BackupMeta meta, 
                                         final RestoreOptions opts) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm Restore")
                .setMessage("Mode: " + opts.getDescription() + "\n\nContinue?")
                .setPositiveButton("Restore", (dialog, which) -> {
                    startRestore(backupDir, meta, opts, false, false, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmForceRestore(File backupDir, BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Force Restore")
                .setMessage("Force mode will continue even if errors occur. This may leave the app in an inconsistent state.\n\nAre you sure?")
                .setPositiveButton("Force Restore", (dialog, which) -> {
                    startRestore(backupDir, meta,
                        new RestoreOptions(RestoreOptions.RestoreMode.FULL),
                        false, true, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Verify Backup
     */
    private void verifyBackup(File backupDir, BackupMeta meta) {
        progressHelper.show("Verifying Backup", "Please wait...");
        
        new Thread(() -> {
            final com.appbackup.pro.core.BackupVerifier.VerifyResult result = 
                com.appbackup.pro.core.BackupVerifier.verifyBackup(backupDir, meta);
            
            runOnUiThread(() -> {
                progressHelper.dismiss();
                String title = result.success ? "✅ Backup is Valid" : "❌ Backup has Issues";
                
                TextView textView = new TextView(this);
                textView.setText(result.summary());
                textView.setPadding(40, 20, 40, 20);
                textView.setTextSize(13);
                
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(textView)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
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
     * ⭐ شروع ریستور با همه‌ی گزینه‌ها
     */
    private void startRestore(final File backupDir, final BackupMeta meta,
                              final RestoreOptions options,
                              final boolean dryRun, final boolean forceMode, 
                              final boolean skipVerify) {
        String title = dryRun ? "Dry Run" : "Restoring Backup";
        progressHelper.show(title, "Starting...");

        new Thread(() -> {
            RestoreEngine engine = new RestoreEngine(MainActivity.this)
                    .setOptions(options)
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

    private void showResultDialog(BackupResult result, String operation) {
        String title = result.isSuccess() ? operation + " Successful ✓" : operation + " Failed ✗";
        
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
