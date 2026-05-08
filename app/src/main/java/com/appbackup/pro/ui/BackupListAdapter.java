package com.appbackup.pro.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.appbackup.pro.R;
import com.appbackup.pro.core.BackupRepository;
import com.appbackup.pro.models.BackupMeta;
import com.appbackup.pro.utils.FileUtils;

import java.io.File;
import java.util.List;

/**
 * Adapter برای نمایش لیست بکاپ‌های ذخیره‌شده
 */
public class BackupListAdapter extends RecyclerView.Adapter<BackupListAdapter.ViewHolder> {

    private List<File> backups;
    private final BackupRepository repository;
    private final OnBackupClickListener listener;

    public interface OnBackupClickListener {
        void onRestoreClick(File backupDir, BackupMeta meta);
        void onDeleteClick(File backupDir, BackupMeta meta);
        void onRenameClick(File backupDir, BackupMeta meta);
    }

    public BackupListAdapter(List<File> backups, BackupRepository repository, OnBackupClickListener listener) {
        this.backups = backups;
        this.repository = repository;
        this.listener = listener;
    }

    public void updateData(List<File> newBackups) {
        this.backups = newBackups;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_backup, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final File backupDir = backups.get(position);
        final BackupMeta meta = repository.readMeta(backupDir);

        if (meta == null) {
            holder.tvName.setText(backupDir.getName());
            holder.tvDate.setText("Invalid backup");
            holder.tvDetails.setText("");
            return;
        }

        holder.tvName.setText(meta.getBackupName());
        holder.tvPackage.setText(meta.getPackageName());
        holder.tvDate.setText(FileUtils.formatDate(meta.getCreatedAt()));

        // نمایش جزئیات بکاپ
        StringBuilder details = new StringBuilder();
        details.append(FileUtils.formatSize(meta.getTotalSize()));
        details.append(" • v").append(meta.getVersionName());

        StringBuilder contents = new StringBuilder();
        if (meta.hasApk()) contents.append("APK ");
        if (meta.hasSplitApks()) contents.append("Splits ");
        if (meta.hasInternalData()) contents.append("Data ");
        if (meta.hasDeviceProtectedData()) contents.append("DE ");
        if (meta.hasExternalData()) contents.append("Ext ");
        if (meta.hasObb()) contents.append("OBB ");

        holder.tvDetails.setText(details.toString());
        holder.tvContents.setText(contents.toString().trim());

        holder.btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onRestoreClick(backupDir, meta);
            }
        });

        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onDeleteClick(backupDir, meta);
            }
        });

        holder.btnRename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onRenameClick(backupDir, meta);
            }
        });
    }

    @Override
    public int getItemCount() {
        return backups.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvPackage;
        TextView tvDate;
        TextView tvDetails;
        TextView tvContents;
        View btnRestore;
        View btnDelete;
        View btnRename;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_backup_name);
            tvPackage = itemView.findViewById(R.id.tv_backup_package);
            tvDate = itemView.findViewById(R.id.tv_backup_date);
            tvDetails = itemView.findViewById(R.id.tv_backup_details);
            tvContents = itemView.findViewById(R.id.tv_backup_contents);
            btnRestore = itemView.findViewById(R.id.btn_restore);
            btnDelete = itemView.findViewById(R.id.btn_delete);
            btnRename = itemView.findViewById(R.id.btn_rename);
        }
    }
}