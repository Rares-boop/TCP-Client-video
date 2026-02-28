package com.example.tcpclient.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.tcpclient.utils.ClientKeyManager;
import com.example.tcpclient.utils.ConfigReader;
import com.example.tcpclient.R;
import com.example.tcpclient.storage.SecureStorage;
import com.example.tcpclient.network.TcpConnection;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.security.KeyPair;

import chat.models.User;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import chat.security.CryptoHelper;


public class RegisterActivity extends AppCompatActivity {
    private ConfigReader config;
    private SharedPreferences preferences;
    private final Gson gson = new Gson();
    private static final String TAG = "RegisterActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_register);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        config = new ConfigReader(this);

        try {
            preferences = SecureStorage.getEncryptedPrefs(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load encrypted preferences, falling back to standard", e);
            preferences = getSharedPreferences("ChatPrefs", MODE_PRIVATE);
        }
    }

    public void handleAccount(View view) {
        EditText usernameField = findViewById(R.id.editTextText);
        EditText passwordField = findViewById(R.id.editTextTextPassword2);
        EditText confirmedPasswordField = findViewById(R.id.editTextTextPassword3);
        CheckBox checkBox = findViewById(R.id.checkBoxKeepSignedInRegister);

        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();
        String confirmedPassword = confirmedPasswordField.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty() || confirmedPassword.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirmedPassword)) {
            Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Registering...", Toast.LENGTH_SHORT).show();
        view.setEnabled(false);

        new Thread(() -> {
            try {
                TcpConnection.close();

                TcpConnection.connect(config.getServerIp(), config.getServerPort());

                ChatDtos.AuthDto registerData = new ChatDtos.AuthDto(username, password);
                NetworkPacket request = new NetworkPacket(PacketType.REGISTER_REQUEST, 0, registerData);

                TcpConnection.sendPacket(request);

                NetworkPacket responsePacket = TcpConnection.readNextPacket();

                runOnUiThread(() -> {
                    view.setEnabled(true);

                    if (responsePacket != null && responsePacket.getType() == PacketType.REGISTER_RESPONSE) {
                        handleRegisterResponse(responsePacket, username, password, checkBox.isChecked());
                    } else {
                        showSnackbar("Server Error: Invalid response.");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Connection error during registration", e);
                TcpConnection.close();
                runOnUiThread(() -> {
                    view.setEnabled(true);
                    showSnackbar("Connection Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void handleRegisterResponse(NetworkPacket packet, String userStr, String passStr, boolean save) {
        try {
            JsonElement payload = packet.getPayload();

            if (payload.isJsonObject()) {
                User user = gson.fromJson(payload, User.class);

                if (user != null && user.getUsername() != null) {
                    TcpConnection.setCurrentUserId(user.getId());

                    SharedPreferences.Editor editor = preferences.edit();
                    if (save) {
                        editor.putString("username", userStr);
                        editor.putString("password", passStr);
                    } else {
                        editor.remove("username");
                        editor.remove("password");
                    }
                    editor.apply();

                    Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show();

                    new Thread(() -> {
                        try {
                            ClientKeyManager clientKeyManager = new ClientKeyManager(this);

                            KeyPair identityKP = CryptoHelper.generateDilithiumKeys();
                            KeyPair preKeyKP = CryptoHelper.generateKyberKeys();

                            String ikPub = android.util.Base64.encodeToString(identityKP.getPublic().getEncoded(), android.util.Base64.NO_WRAP);
                            String ikPriv = android.util.Base64.encodeToString(identityKP.getPrivate().getEncoded(), android.util.Base64.NO_WRAP);

                            String spkPub = android.util.Base64.encodeToString(preKeyKP.getPublic().getEncoded(), android.util.Base64.NO_WRAP);
                            String spkPriv = android.util.Base64.encodeToString(preKeyKP.getPrivate().getEncoded(), android.util.Base64.NO_WRAP);

                            byte[] signatureBytes = CryptoHelper.signData(
                                    identityKP.getPrivate(),
                                    preKeyKP.getPublic().getEncoded()
                            );
                            String signatureBase64 = android.util.Base64.encodeToString(signatureBytes, android.util.Base64.NO_WRAP);

                            clientKeyManager.saveMyIdentityKeys(ikPub, ikPriv, spkPub, spkPriv);

                            ChatDtos.PublishKeysDto dto = new ChatDtos.PublishKeysDto(ikPub, spkPub, signatureBase64);
                            TcpConnection.sendPacket(new NetworkPacket(PacketType.PUBLISH_KEYS, user.getId(), dto));

                            Log.i(TAG, "Keys generated and sent!");

                            runOnUiThread(() -> {
                                Toast.makeText(this, "Secure Account Activated!", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                                startActivity(intent);
                                finish();
                            });

                        } catch (Exception e) {
                            Log.e(TAG, "Critical error generating keys", e);
                            runOnUiThread(() -> showSnackbar("Critical error generating keys: " + e.getMessage()));
                        }
                    }).start();
                }
            } else {
                String errorMsg = gson.fromJson(payload, String.class);
                showSnackbar(errorMsg);

                ((EditText)findViewById(R.id.editTextTextPassword2)).setText("");
                ((EditText)findViewById(R.id.editTextTextPassword3)).setText("");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing register response", e);
            showSnackbar("Error processing server response.");
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(
                findViewById(android.R.id.content),
                message,
                Snackbar.LENGTH_LONG
        ).setBackgroundTint(Color.RED).setTextColor(Color.WHITE).show();
    }
}

