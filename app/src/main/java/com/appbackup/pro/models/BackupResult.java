package com.appbackup.pro.models;

/**
 * نتیجه‌ی یک عملیات بکاپ یا ریستور
 */
public class BackupResult {
    private boolean success;
    private String message;
    private String errorDetails;
    private String backupPath;
    private long durationMs;

    public BackupResult() {
    }

    public BackupResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static BackupResult success(String message) {
        return new BackupResult(true, message);
    }

    public static BackupResult success(String message, String backupPath) {
        BackupResult result = new BackupResult(true, message);
        result.backupPath = backupPath;
        return result;
    }

    public static BackupResult failure(String message) {
        return new BackupResult(false, message);
    }

    public static BackupResult failure(String message, String errorDetails) {
        BackupResult result = new BackupResult(false, message);
        result.errorDetails = errorDetails;
        return result;
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getErrorDetails() { return errorDetails; }
    public void setErrorDetails(String errorDetails) { this.errorDetails = errorDetails; }

    public String getBackupPath() { return backupPath; }
    public void setBackupPath(String backupPath) { this.backupPath = backupPath; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}