package net.slickerius;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

final class CredentialStore {
    static final String PREFS = "credentials";
    static final String KEY_SSID = "ssid";
    static final String KEY_PASSWORD = "password";
    static final String DEFAULT_SSID = "DIRECT-FS FreeSHare";

    private CredentialStore() {
    }

    static String getSsid(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SSID, DEFAULT_SSID);
    }

    static String getOrCreatePassword(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = preferences.getString(KEY_PASSWORD, "");
        if (isValidPassword(saved)) {
            return saved;
        }
        String value = generatePassword();
        preferences.edit().putString(KEY_PASSWORD, value).apply();
        return value;
    }

    static String refreshPassword(Context context) {
        String value = generatePassword();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PASSWORD, value)
                .apply();
        return value;
    }

    private static String generatePassword() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder generated = new StringBuilder("FS-");
        for (int index = 0; index < 12; index++) {
            generated.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return generated.toString();
    }

    static void save(Context context, String ssid, String password) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SSID, ssid)
                .putString(KEY_PASSWORD, password)
                .apply();
    }

    static boolean isValidSsid(String ssid) {
        if (ssid == null || !ssid.matches("^DIRECT-[A-Za-z0-9]{2}.*$")) {
            return false;
        }
        return ssid.getBytes(StandardCharsets.UTF_8).length <= 32;
    }

    static boolean isValidPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 63) {
            return false;
        }
        for (int index = 0; index < password.length(); index++) {
            char value = password.charAt(index);
            if (value < 0x20 || value > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
