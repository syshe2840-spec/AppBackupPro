package com.appbackup.pro;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
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
import com.appbackup.pro.core.AuthManager;
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
    
    // Auth
    private AuthManager authManager;
    private Handler periodicHandler;
    private Runnable periodicCheck;
    private boolean uiInitialized = false;

    @Override
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        authManager = AuthManager.getInstance(this);
        progressHelper = new ProgressDialogHelper(this);

        // چک اینترنت
        if (!authManager.hasInternet()) {
            showNoInternetDialog();
            return;
        }
        
        // چک license محلی
        if (!authManager.hasStoredLicense()) {
            showLoginDialog();
            return;
        }
        
        // چک سرور
        verifyOnStartup();
    }
    
    private void showLoginDialog() {
        EditText editText = new EditText(this);
        editText.setHint("XXXX-XXXX-XXXX-XXXX");
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        editText.setPadding(40, 20, 40, 20);
        
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🔐 Activation Required")
                .setMessage("Enter your license key to continue:")
                .setView(editText)
                .setCancelable(false)
                .setPositiveButton("Activate", null)
                .setNegativeButton("Exit", (d, w) -> finish())
                .create();
        
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String key = editText.getText().toString().trim();
                    if (key.isEmpty()) {
                        editText.setError("Required");
                        return;
                    }
                    performLogin(dialog, key);
                });
        });
        
        dialog.show();
    }

        /**
     * ⭐ Dialog عدم اتصال
     */
    private void showNoInternetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("📡 No Internet")
                .setMessage("This app requires an active internet connection.\n\n"
                    + "Please connect to Wi-Fi or mobile data and try again.")
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> recreate())
                .setNegativeButton("Exit", (d, w) -> finish())
                .show();
    }
    
    /**
     * ⭐ چک سرور موقع باز شدن اپ (هر بار)
     */
    private void verifyOnStartup() {
        progressHelper.show("Verifying", "Connecting to server...");
        
        new Thread(() -> {
            final boolean ok = authManager.verifyOnlineSync();
            
            runOnUiThread(() -> {
                progressHelper.dismiss();
                
                if (ok) {
                    initializeApp();
                } else {
                    // اگه auth fail کرد، یا server unreachable یا license invalid
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("⚠️ Verification Failed")
                        .setMessage("Could not verify your license.\n\n"
                            + "Possible reasons:\n"
                            + "• No stable internet\n"
                            + "• Server is down\n"
                            + "• License deactivated\n"
                            + "• Session expired")
                        .setCancelable(false)
                        .setPositiveButton("Retry", (d, w) -> recreate())
                        .setNegativeButton("Re-activate", (d, w) -> {
                            authManager.logout();
                            recreate();
                        })
                        .setNeutralButton("Exit", (d, w) -> finish())
                        .show();
                }
            });
        }).start();
    }
    private void performLogin(final AlertDialog dialog, String licenseKey) {
        progressHelper.show("Activating", "Connecting to server...");
        
        authManager.login(licenseKey, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    progressHelper.dismiss();
                    dialog.dismiss();
                    Toast.makeText(MainActivity.this, "✓ Activated", Toast.LENGTH_SHORT).show();
                    initializeApp();
                });
            }
            
            @Override
            public void onFailure(final String error) {
                runOnUiThread(() -> {
                    progressHelper.dismiss();
                    Toast.makeText(MainActivity.this, "❌ " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private void checkAuthAndContinue() {
        progressHelper.show("Verifying", "Please wait...");
        
        authManager.checkAuth(new AuthManager.AuthCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    progressHelper.dismiss();
                    initializeApp();
                });
            }
            
            @Override
            public void onFailure(final String error) {
                runOnUiThread(() -> {
                    progressHelper.dismiss();
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Authentication Failed")
                        .setMessage(error)
                        .setCancelable(false)
                        .setPositiveButton("Re-activate", (d, w) -> {
                            authManager.logout();
                            showLoginDialog();
                        })
                        .setNegativeButton("Exit", (d, w) -> finish())
                        .show();
                });
            }
        });
    }
    
    private void initializeApp() {
        if (uiInitialized) return;
        uiInitialized = true;
        
        initViews();
        repository = new BackupRepository(this);

        if (!PermissionHelper.hasStoragePermission(this)) {
            PermissionHelper.requestStoragePermission(this);
        }

        checkRootStatus();
        loadApps();
        startPeriodicAuthCheck();
    }
    
 private void startPeriodicAuthCheck() {
        periodicHandler = new Handler(Looper.getMainLooper());
        periodicCheck = new Runnable() {
            @Override
            public void run() {
                authManager.checkAuth(new AuthManager.AuthCallback() {
                    @Override
                    public void onSuccess() {
                        Log.d("AppBackupPro_AUTH", "Periodic check OK (failures: 0)");
                    }
                    
                    @Override
                    public void onFailure(final String error) {
                        runOnUiThread(() -> {
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("⚠️ Session Expired")
                                .setMessage("Your session has expired.\n\n"
                                    + "Reason: " + error + "\n\n"
                                    + "Please re-activate to continue using the app.")
                                .setCancelable(false)
                                .setPositiveButton("Re-activate", (d, w) -> {
                                    authManager.logout();
                                    recreate();
                                })
                                .setNegativeButton("Exit", (d, w) -> finish())
                                .show();
                        });
                    }
                });
                
                // دوباره ۱۵ دقیقه‌ی بعد
                periodicHandler.postDelayed(this, 15 * 60 * 1000);
            }
        };
        
        periodicHandler.postDelayed(periodicCheck, 15 * 60 * 1000);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (periodicHandler != null && periodicCheck != null) {
            periodicHandler.removeCallbacks(periodicCheck);
        }
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
                if (adapter != null) adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnViewBackups.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, BackupListActivity.class));
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
                    Toast.makeText(MainActivity.this, "Root required", Toast.LENGTH_LONG).show();
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

    @Override
    public void onBackupClick(AppInfo app) {
        AppWarnings.AppWarning warning = AppWarnings.getWarning(app.getPackageName());
        
        if (warning.level == AppWarnings.WarningLevel.CRITICAL) {
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ Backup Warning")
                    .setMessage(warning.message + "\n\nReason: " + warning.reason)
                    .setPositiveButton("Backup Anyway", (d, w) -> showBackupDialog(app))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (warning.level != AppWarnings.WarningLevel.NONE) {
            new AlertDialog.Builder(this)
                    .setTitle("ℹ️ Note")
                    .setMessage(warning.message)
                    .setPositiveButton("Backup", (d, w) -> showBackupDialog(app))
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
                .setView(editText)
                .setPositiveButton("Backup", (d, w) -> startBackup(app, editText.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRestoreClick(AppInfo app) {
        List<File> backups = repository.getBackupsForApp(app.getPackageName());
        if (backups.isEmpty()) {
            Toast.makeText(this, "No backups found", Toast.LENGTH_SHORT).show();
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
                .setTitle("Select backup")
                .setItems(options, (d, which) -> showRestoreModeDialog(backups.get(which)))
                .show();
    }

    private void showRestoreModeDialog(final File backupDir) {
        final BackupMeta meta = repository.readMeta(backupDir);
        if (meta == null) {
            Toast.makeText(this, "Cannot read backup", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] options = {
                "🔄 Full Restore",
                "🎯 Selective Restore...",
                "👁 Dry Run",
                "🔍 Verify Backup",
                "⚙️ Advanced Options..."
        };

        new AlertDialog.Builder(this)
                .setTitle("Restore: " + meta.getBackupName())
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
        String[] options = {
                "📦 APK Only", "💾 Data Only", "🗄 Databases Only",
                "⚙️ Shared Prefs Only", "📁 Files Only", "📂 External Data Only",
                "🎮 OBB Only", "🛠 Custom..."
        };

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
        String[] components = {"Install APK", "Internal Data", "Databases", "Shared Prefs",
                "Files", "DE Data", "External Data", "OBB", "Native Libs", "KeyStore", "Permissions"};
        boolean[] checked = {meta.hasApk(), meta.hasInternalData(), meta.hasInternalData(),
                meta.hasInternalData(), meta.hasInternalData(), meta.hasDeviceProtectedData(),
                meta.hasExternalData(), meta.hasObb(), meta.hasNativeLibs(), meta.hasKeystore(),
                meta.hasPermissions()};

        new AlertDialog.Builder(this)
                .setTitle("Custom Restore")
                .setMultiChoiceItems(components, checked, (d, which, isChecked) -> checked[which] = isChecked)
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
        String[] options = {"💪 Force Restore", "⚡ Quick Restore", "👥 Restore to user...", "🛠 Force + Quick + Custom"};
        new AlertDialog.Builder(this)
                .setTitle("Advanced Options")
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
                    Toast.makeText(this, "Only main user available", Toast.LENGTH_SHORT).show();
                    confirmFullRestore(backupDir, meta);
                    return;
                }
                String[] userNames = new String[users.size()];
                for (int i = 0; i < users.size(); i++) {
                    MultiUserHelper.AndroidUser u = users.get(i);
                    userNames[i] = "User " + u.userId + ": " + u.userName + (u.isMain ? " (Main)" : "");
                }
                new AlertDialog.Builder(this)
                        .setTitle("Restore to which user?")
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
                .setTitle("Execution mode")
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
        String[] components = {"APK", "Internal Data", "DE Data", "External Data",
                "OBB", "Native Libs", "KeyStore", "Permissions"};
        boolean[] checked = {meta.hasApk(), meta.hasInternalData(), meta.hasDeviceProtectedData(),
                meta.hasExternalData(), meta.hasObb(), meta.hasNativeLibs(),
                meta.hasKeystore(), meta.hasPermissions()};

        new AlertDialog.Builder(this)
                .setTitle("Select components")
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
                .setMessage("Replace current app data with backup?")
                .setPositiveButton("Restore", (d, w) -> {
                    startRestore(backupDir, meta, new RestoreOptions(RestoreOptions.RestoreMode.FULL), false, false, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmSelectiveRestore(final File backupDir, final BackupMeta meta, final RestoreOptions opts) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm Restore")
                .setMessage("Mode: " + opts.getDescription())
                .setPositiveButton("Restore", (d, w) -> startRestore(backupDir, meta, opts, false, false, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmForceRestore(File backupDir, BackupMeta meta) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Force Restore")
                .setMessage("Continue even if errors occur?")
                .setPositiveButton("Force", (d, w) -> startRestore(backupDir, meta,
                        new RestoreOptions(RestoreOptions.RestoreMode.FULL), false, true, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void verifyBackup(File backupDir, BackupMeta meta) {
        progressHelper.show("Verifying", "Please wait...");
        new Thread(() -> {
            final com.appbackup.pro.core.BackupVerifier.VerifyResult result = 
                com.appbackup.pro.core.BackupVerifier.verifyBackup(backupDir, meta);
            runOnUiThread(() -> {
                progressHelper.dismiss();
                String title = result.success ? "✅ Valid" : "❌ Has Issues";
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

   private void startBackup(final AppInfo app, final String name) {
        // ⭐ چک اینترنت
        if (!authManager.hasInternet()) {
            showNoInternetForOperation("backup");
            return;
        }
        
        // ⭐ چک online با سرور
        progressHelper.show("Verifying access", "Checking license...");
        new Thread(() -> {
            final boolean ok = authManager.verifyOnlineSync();
            
            if (!ok) {
                runOnUiThread(() -> {
                    progressHelper.dismiss();
                    showAuthFailDialog("Cannot start backup");
                });
                return;
            }
            
            // OK - شروع backup
            runOnUiThread(() -> progressHelper.show("Creating Backup", "Starting..."));
            
            BackupEngine engine = new BackupEngine(MainActivity.this);
            engine.setProgressCallback((message, percent) -> progressHelper.update(message, percent));
            final BackupResult result = engine.backup(app, name);
            runOnUiThread(() -> {
                progressHelper.dismiss();
                showResultDialog(result, "Backup");
            });
        }).start();
    }

  private void startRestore(final File backupDir, final BackupMeta meta,
                              final RestoreOptions options,
                              final boolean dryRun, final boolean forceMode, 
                              final boolean skipVerify) {
        // ⭐ Dry Run خطری نداره، چک نمی‌کنیم
        if (dryRun) {
            doActualRestore(backupDir, meta, options, true, forceMode, skipVerify);
            return;
        }
        
        // ⭐ چک اینترنت
        if (!authManager.hasInternet()) {
            showNoInternetForOperation("restore");
            return;
        }
        
        // ⭐ چک online با سرور
        progressHelper.show("Verifying access", "Checking license...");
        new Thread(() -> {
            final boolean ok = authManager.verifyOnlineSync();
            
            if (!ok) {
                runOnUiThread(() -> {
                    progressHelper.dismiss();
                    showAuthFailDialog("Cannot start restore");
                });
                return;
            }
            
            runOnUiThread(() -> progressHelper.show("Restoring", "Starting..."));
            doActualRestore(backupDir, meta, options, false, forceMode, skipVerify);
        }).start();
    }
    
    private void doActualRestore(final File backupDir, final BackupMeta meta,
                                  final RestoreOptions options,
                                  final boolean dryRun, final boolean forceMode,
                                  final boolean skipVerify) {
        if (dryRun) progressHelper.show("Dry Run", "Starting...");
        
        new Thread(() -> {
            RestoreEngine engine = new RestoreEngine(MainActivity.this)
                    .setOptions(options)
                    .setDryRun(dryRun)
                    .setForceMode(forceMode)
                    .setSkipVerification(skipVerify);
            engine.setProgressCallback((message, percent) -> progressHelper.update(message, percent));
            final BackupResult result = engine.restore(backupDir, meta);
            runOnUiThread(() -> {
                progressHelper.dismiss();
                showResultDialog(result, dryRun ? "Dry Run" : "Restore");
                if (result.isSuccess() && !dryRun) loadApps();
            });
        }).start();
    }
    
    private void doActualRestore(final File backupDir, final BackupMeta meta,
                                  final RestoreOptions options,
                                  final boolean dryRun, final boolean forceMode,
                                  final boolean skipVerify) {
        new Thread(() -> {
            RestoreEngine engine = new RestoreEngine(MainActivity.this)
                    .setOptions(options)
                    .setDryRun(dryRun)
                    .setForceMode(forceMode)
                    .setSkipVerification(skipVerify);
            engine.setProgressCallback((message, percent) -> progressHelper.update(message, percent));
            final BackupResult result = engine.restore(backupDir, meta);
            runOnUiThread(() -> {
                progressHelper.dismiss();
                showResultDialog(result, dryRun ? "Dry Run" : "Restore");
                if (result.isSuccess() && !dryRun) loadApps();
            });
        }).start();
    }
/**
     * ⭐ Dialog قوی برای auth failure
     */
    private void showAuthFailDialog(String context) {
        String message = context + ".\n\n" 
            + "Could not verify your license. This could be because:\n\n"
            + "• No internet connection\n"
            + "• Server is down\n"
            + "• License has been deactivated\n"
            + "• Session expired\n\n"
            + "Please check your connection and try again.";
        
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Authentication Failed")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> recreate())
                .setNegativeButton("Re-activate", (d, w) -> {
                    authManager.logout();
                    recreate();
                })
                .setNeutralButton("Exit", (d, w) -> finish())
                .show();
    }
    /**
     * ⭐ Dialog عدم اتصال موقع backup/restore
     */
    private void showNoInternetForOperation(String operation) {
        new AlertDialog.Builder(this)
                .setTitle("📡 No Internet")
                .setMessage("Cannot " + operation + ".\n\n"
                    + "Internet connection is required for this operation.\n"
                    + "Please connect and try again.")
                .setPositiveButton("OK", null)
                .show();
    }
    
    /**
     * ⭐ Dialog شکست auth
     */
    private void showAuthFailDialog(String context) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Authentication Failed")
                .setMessage(context + ".\n\n"
                    + "Could not verify your license with the server.\n\n"
                    + "Possible reasons:\n"
                    + "• Internet connection unstable\n"
                    + "• License has been deactivated\n"
                    + "• Session has expired\n"
                    + "• Server is temporarily down")
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> recreate())
                .setNegativeButton("Re-activate", (d, w) -> {
                    authManager.logout();
                    recreate();
                })
                .setNeutralButton("Exit", (d, w) -> finish())
                .show();
    }
    private void showResultDialog(BackupResult result, String operation) {
        String title = result.isSuccess() ? operation + " ✓" : operation + " ✗";
        TextView tv = new TextView(this);
        tv.setText(result.getMessage());
        tv.setPadding(40, 20, 40, 20);
        tv.setTextSize(13);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(tv)
                .setPositiveButton("OK", null)
                .show();
    }
}
