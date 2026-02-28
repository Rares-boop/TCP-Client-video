package com.example.tcpclient.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class ClientKeyManager {
    private static final String TAG = "ClientKeyManager";
    private static final String PREF_FILE_NAME = "secure_chat_keys";
    private static final String KEY_IK_PUB = "MY_IDENTITY_PUB_DILITHIUM";
    private static final String KEY_IK_PRIV = "MY_IDENTITY_PRIV_DILITHIUM";
    private static final String KEY_SPK_PUB = "MY_PREKEY_PUB_KYBER";
    private static final String KEY_SPK_PRIV = "MY_PREKEY_PRIV_KYBER";
    private SharedPreferences securePrefs;

    public ClientKeyManager(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            this.securePrefs = EncryptedSharedPreferences.create(
                    context,
                    PREF_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to initialize secure prefs for KeyManager. Using fallback!", e);
            this.securePrefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    public void saveKey(int chatId, String base64Key) {
        securePrefs.edit().putString(String.valueOf(chatId), base64Key).apply();
    }

    public SecretKey getKey(int chatId) {
        String base64 = securePrefs.getString(String.valueOf(chatId), null);
        if (base64 == null) return null;

        byte[] decoded = Base64.decode(base64, Base64.NO_WRAP);
        return new SecretKeySpec(decoded, "AES");
    }
    public void saveMyIdentityKeys(String ikPub, String ikPriv, String spkPub, String spkPriv) {
        securePrefs.edit()
                .putString(KEY_IK_PUB, ikPub)
                .putString(KEY_IK_PRIV, ikPriv)
                .putString(KEY_SPK_PUB, spkPub)
                .putString(KEY_SPK_PRIV, spkPriv)
                .apply();
    }
    public String getMyPreKeyPrivateKey() {
        return securePrefs.getString(KEY_SPK_PRIV, null);
    }
}
