package com.appbackup.pro.core;

import java.util.HashMap;
import java.util.Map;

/**
 * لیست اپ‌های معروف که بعد از ریستور مشکل دارن
 * به کاربر هشدار می‌ده قبل از بکاپ یا ریستور
 */
public class AppWarnings {
    
    public enum WarningLevel {
        NONE,           // هیچ مشکلی نیست
        INFO,           // اطلاعاتی
        WARNING,        // ممکنه نیاز به re-login باشه
        CRITICAL        // به احتمال زیاد کار نمی‌کنه
    }
    
    public static class AppWarning {
        public WarningLevel level;
        public String message;
        public String reason;
        
        public AppWarning(WarningLevel level, String message, String reason) {
            this.level = level;
            this.message = message;
            this.reason = reason;
        }
    }
    
    private static final Map<String, AppWarning> KNOWN_APPS = new HashMap<>();
    
    static {
        // VPN های Pro - معمولاً هیچ ابزاری نمی‌تونه ریستورشون کنه
        addCritical("com.expressvpn.vpn", "ExpressVPN", "Hardware-backed authentication");
        addCritical("com.nordvpn.android", "NordVPN", "Server-side device validation");
        addCritical("com.surfshark.vpnclient.android", "Surfshark", "Hardware attestation");
        addCritical("ch.protonvpn.android", "ProtonVPN", "Hardware-backed keys");
        addCritical("net.mullvad.mullvadvpn", "Mullvad VPN", "Account-bound device tokens");
        
        // Authenticator ها - ذاتاً غیرقابل بکاپ
        addCritical("com.google.android.apps.authenticator2", "Google Authenticator", 
            "TOTP secrets are hardware-backed");
        addCritical("com.azure.authenticator", "Microsoft Authenticator", 
            "Hardware-backed authentication");
        addCritical("com.authy.authy", "Authy", "Server-side device registration");
        
        // اپ‌های بانکی ایرانی
        addCritical("com.behpardakht.android.app", "به‌پرداخت", "Bank security policies");
        addCritical("com.parsian.bank.android", "بانک پارسیان", "Bank security policies");
        addCritical("com.ada.mbank.melli", "بانک ملی", "Bank security policies");
        addCritical("com.sepehr.android", "بانک سپه", "Bank security policies");
        addCritical("ir.tejaratbank.tata.mobile.android.tejarat", "بانک تجارت", "Bank security policies");
        
        // Crypto Wallets
        addCritical("com.wallet.crypto.trustapp", "Trust Wallet", 
            "Encrypted with device-bound keys");
        addCritical("io.metamask", "MetaMask", "Encrypted vault");
        addCritical("com.binance.dev", "Binance", "Hardware-backed authentication");
        
        // پرداخت
        addCritical("com.google.android.apps.walletnfcrel", "Google Pay", 
            "Hardware-backed payment tokens");
        addCritical("com.paypal.android.p2pmobile", "PayPal", "Server-side device validation");
        
        // پیام‌رسان‌ها - معمولاً نیاز به re-login دارن
        addWarning("com.whatsapp", "WhatsApp", "Phone number verification required");
        addWarning("org.telegram.messenger", "Telegram", "May need SMS verification");
        addWarning("com.viber.voip", "Viber", "Phone number verification required");
        
        // اپ‌های Google
        addWarning("com.google.android.apps.docs", "Google Drive", "Account re-authentication");
        addWarning("com.google.android.apps.maps", "Google Maps", "Login required");
        addWarning("com.google.android.youtube", "YouTube", "Login required");
        
        // Streaming
        addWarning("com.netflix.mediaclient", "Netflix", "Re-login may be needed");
        addWarning("com.spotify.music", "Spotify", "May need to re-login");
        
        // اپ‌های پشتیبانی از device fingerprint
        addInfo("com.instagram.android", "Instagram", "May trigger security alerts");
        addInfo("com.facebook.katana", "Facebook", "May trigger security alerts");
        addInfo("com.zhiliaoapp.musically", "TikTok", "May trigger security alerts");
    }
    
    private static void addCritical(String pkg, String name, String reason) {
        KNOWN_APPS.put(pkg, new AppWarning(
            WarningLevel.CRITICAL,
            name + " uses hardware-backed security and CANNOT be restored. You'll need to re-setup the app.",
            reason
        ));
    }
    
    private static void addWarning(String pkg, String name, String reason) {
        KNOWN_APPS.put(pkg, new AppWarning(
            WarningLevel.WARNING,
            name + " may require re-login after restore.",
            reason
        ));
    }
    
    private static void addInfo(String pkg, String name, String reason) {
        KNOWN_APPS.put(pkg, new AppWarning(
            WarningLevel.INFO,
            name + " may trigger security checks after restore.",
            reason
        ));
    }
    
    /**
     * گرفتن هشدار برای یه پکیج
     */
    public static AppWarning getWarning(String packageName) {
        AppWarning warning = KNOWN_APPS.get(packageName);
        if (warning != null) return warning;
        
        // Heuristic: اپ‌های بانکی معمولاً توی package name کلمه bank دارن
        String lower = packageName.toLowerCase();
        if (lower.contains("bank") || lower.contains("pay") || lower.contains("wallet")) {
            return new AppWarning(WarningLevel.WARNING,
                "This appears to be a financial app. May need re-authentication after restore.",
                "Detected by package name");
        }
        
        if (lower.contains("vpn")) {
            return new AppWarning(WarningLevel.WARNING,
                "VPN apps often have device-bound subscriptions. May not work after restore.",
                "Detected by package name");
        }
        
        if (lower.contains("authenticator") || lower.contains("auth")) {
            return new AppWarning(WarningLevel.WARNING,
                "Authenticator apps may require re-setup.",
                "Detected by package name");
        }
        
        return new AppWarning(WarningLevel.NONE, "", "");
    }
    
    /**
     * چک می‌کنه آیا اپ هشدار داره
     */
    public static boolean hasWarning(String packageName) {
        AppWarning w = getWarning(packageName);
        return w.level != WarningLevel.NONE;
    }
}
