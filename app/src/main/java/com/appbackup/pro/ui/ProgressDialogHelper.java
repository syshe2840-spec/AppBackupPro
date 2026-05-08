package com.appbackup.pro.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.appbackup.pro.R;

/**
 * نمایش دیالوگ پیشرفت برای عملیات بکاپ/ریستور
 */
public class ProgressDialogHelper {
    private final Activity activity;
    private AlertDialog dialog;
    private TextView titleView;
    private TextView messageView;
    private TextView percentView;
    private ProgressBar progressBar;

    public ProgressDialogHelper(Activity activity) {
        this.activity = activity;
    }

    /**
     * نمایش دیالوگ
     */
    public void show(String title, String message) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }

                LayoutInflater inflater = activity.getLayoutInflater();
                View view = inflater.inflate(R.layout.dialog_progress, null);

                titleView = view.findViewById(R.id.tv_progress_title);
                messageView = view.findViewById(R.id.tv_progress_message);
                percentView = view.findViewById(R.id.tv_progress_percent);
                progressBar = view.findViewById(R.id.progress_bar);

                titleView.setText(title);
                messageView.setText(message);
                percentView.setText("0%");
                progressBar.setProgress(0);

                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setView(view);
                builder.setCancelable(false);

                dialog = builder.create();
                dialog.show();
            }
        });
    }

    /**
     * بروزرسانی پیشرفت
     */
    public void update(final String message, final int percent) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) {
                    if (messageView != null) {
                        messageView.setText(message);
                    }
                    if (percentView != null) {
                        percentView.setText(percent + "%");
                    }
                    if (progressBar != null) {
                        progressBar.setProgress(percent);
                    }
                }
            }
        });
    }

    /**
     * بستن دیالوگ
     */
    public void dismiss() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                    dialog = null;
                }
            }
        });
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}