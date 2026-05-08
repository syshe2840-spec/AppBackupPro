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

/**
 * صفحه‌ی اصلی - نمایش لیست اپ‌ها و دکمه‌های بکاپ/ریستور
 */
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

        // چک کردن permission
        if (!PermissionHelper.hasStoragePermission(this)) {
            PermissionHelper.requestStoragePermission(this);
        }

        // چک کردن root
        checkRootStatus();

        // لود کردن لیست اپ‌ها
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // وقتی برمی‌گردیم از صفحه‌ی بکاپ‌ها، لیست رو رفرش می‌کنیم
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

        // toggle برای system apps
        switchSystemApps.setChecked(showSystemApps);
        switchSystemApps.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            loadApps();
        });

        // search
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

        // دکمه‌ی نمایش بکاپ‌ها
        btnViewBackups.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, BackupListActivity.class);
            startActivity(intent);
        });
    }

    /**
     * چک کردن وضعیت root
     */
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

    /**
     * لود کردن لیست اپ‌های نصب‌شده
     */
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
     * کلیک روی دکمه‌ی Backup
     */
    @Override
    public void onBackupClick(AppInfo app) {
        // دیالوگ گرفتن اسم بکاپ (اختیاری)
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
     * کلیک روی دکمه‌ی Restore
     */
    @Override
    public void onRestoreClick(AppInfo app) {
        // پیدا کردن بکاپ‌های این اپ
        List<File> backups = repository.getBackupsForApp(app.getPackageName());
        if (backups.isEmpty()) {
            Toast.makeText(this, "No backups found for this app", Toast.LENGTH_SHORT).show();
            return;
        }

        // اگه فقط یه بکاپ بود، مستقیم برو
        if (backups.size() == 1) {
            confirmRestore(backups.get(0));
            return;
        }

        // اگه چند تا بود، انتخاب بده
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
     * تأیید نهایی ریستور
     */
    private void confirmRestore(final File backupDir) {
        final BackupMeta meta = repository.readMeta(backupDir);
        if (meta == null) {
            Toast.makeText(this, "Cannot read backup metadata", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Restore " + meta.getBackupName())
                .setMessage("This will replace current app data. Continue?")
                .setPositiveButton("Restore", (dialog, which) -> {
                    startRestore(backupDir, meta);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * شروع عملیات بکاپ
     */
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
                if (result.isSuccess()) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Backup Successful ✓")
                            .setMessage(result.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Backup Failed ✗")
                            .setMessage(result.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }

    /**
     * شروع عملیات ریستور
     */
    private void startRestore(final File backupDir, final BackupMeta meta) {
        progressHelper.show("Restoring Backup", "Starting...");

        new Thread(() -> {
            RestoreEngine engine = new RestoreEngine(MainActivity.this);
            engine.setProgressCallback((message, percent) -> {
                progressHelper.update(message, percent);
            });

            final BackupResult result = engine.restore(backupDir, meta);

            runOnUiThread(() -> {
                progressHelper.dismiss();
                if (result.isSuccess()) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Restore Successful ✓")
                            .setMessage(result.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                    // رفرش لیست اپ‌ها
                    loadApps();
                } else {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Restore Failed ✗")
                            .setMessage(result.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }
}