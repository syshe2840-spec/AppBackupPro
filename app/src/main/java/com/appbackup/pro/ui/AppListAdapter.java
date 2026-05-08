package com.appbackup.pro.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.appbackup.pro.R;
import com.appbackup.pro.models.AppInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter برای نمایش لیست اپ‌های نصب‌شده
 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    private List<AppInfo> apps;
    private List<AppInfo> filteredApps;
    private OnAppClickListener listener;

    public interface OnAppClickListener {
        void onBackupClick(AppInfo app);
        void onRestoreClick(AppInfo app);
    }

    public AppListAdapter(List<AppInfo> apps, OnAppClickListener listener) {
        this.apps = apps;
        this.filteredApps = new ArrayList<>(apps);
        this.listener = listener;
    }

    public void updateData(List<AppInfo> newApps) {
        this.apps = newApps;
        this.filteredApps = new ArrayList<>(newApps);
        notifyDataSetChanged();
    }

    /**
     * فیلتر لیست بر اساس متن جستجو
     */
    public void filter(String query) {
        filteredApps.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredApps.addAll(apps);
        } else {
            String q = query.toLowerCase().trim();
            for (AppInfo app : apps) {
                if (app.getAppName().toLowerCase().contains(q) ||
                        app.getPackageName().toLowerCase().contains(q)) {
                    filteredApps.add(app);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = filteredApps.get(position);

        holder.tvAppName.setText(app.getAppName());
        holder.tvPackageName.setText(app.getPackageName());
        holder.tvVersion.setText("v" + (app.getVersionName() != null ? app.getVersionName() : "?"));

        if (app.getIcon() != null) {
            holder.ivIcon.setImageDrawable(app.getIcon());
        }

        // نشون دادن label "System" برای اپ‌های سیستمی
        if (app.isSystemApp()) {
            holder.tvSystemLabel.setVisibility(View.VISIBLE);
        } else {
            holder.tvSystemLabel.setVisibility(View.GONE);
        }

        holder.btnBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onBackupClick(app);
                }
            }
        });

        holder.btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onRestoreClick(app);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return filteredApps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvAppName;
        TextView tvPackageName;
        TextView tvVersion;
        TextView tvSystemLabel;
        View btnBackup;
        View btnRestore;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_app_icon);
            tvAppName = itemView.findViewById(R.id.tv_app_name);
            tvPackageName = itemView.findViewById(R.id.tv_package_name);
            tvVersion = itemView.findViewById(R.id.tv_version);
            tvSystemLabel = itemView.findViewById(R.id.tv_system_label);
            btnBackup = itemView.findViewById(R.id.btn_backup);
            btnRestore = itemView.findViewById(R.id.btn_restore);
        }
    }
}