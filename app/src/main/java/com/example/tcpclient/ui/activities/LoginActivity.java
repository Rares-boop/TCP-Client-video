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

import com.example.tcpclient.utils.ConfigReader;
import com.example.tcpclient.R;
import com.example.tcpclient.storage.SecureStorage;
import com.example.tcpclient.network.TcpConnection;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import chat.models.User;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;


public class LoginActivity extends AppCompatActivity {
    private ConfigReader config;
    private SharedPreferences preferences;
    private final Gson gson = new Gson();
    private static final String TAG = "LoginActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginLayout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        config = new ConfigReader(this);

        try {
            preferences = SecureStorage.getEncryptedPrefs(getApplicationContext());
        } catch (Exception e) {
            preferences = getSharedPreferences("ChatPrefs", MODE_PRIVATE);
        }

        CheckBox checkBox = findViewById(R.id.checkBoxKeepSignedIn);
        if (preferences.contains("username")) {
            checkBox.setChecked(true);
            checkAutoLogin();
        }
    }

    public void handleLogin(View view) {
        EditText usernameField = findViewById(R.id.textInputEditText);
        EditText passwordField = findViewById(R.id.editTextTextPassword);
        CheckBox checkBox = findViewById(R.id.checkBoxKeepSignedIn);

        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter username and password", Toast.LENGTH_SHORT).show();
            return;
        }

        setButtonsEnabled(false);
        Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                TcpConnection.close();

                TcpConnection.connect(config.getServerIp(), config.getServerPort());

                ChatDtos.AuthDto loginData = new ChatDtos.AuthDto(username, password);
                NetworkPacket requestPacket = new NetworkPacket(PacketType.LOGIN_REQUEST, 0, loginData);
                TcpConnection.sendPacket(requestPacket);

                NetworkPacket responsePacket = TcpConnection.readNextPacket();

                runOnUiThread(() -> {
                    setButtonsEnabled(true);

                    if (responsePacket != null && responsePacket.getType() == PacketType.LOGIN_RESPONSE) {
                        handleLoginResponse(responsePacket, username, password, checkBox.isChecked());
                    } else {
                        showSnackbar("Invalid response from server");
                        TcpConnection.close();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Connection error during login", e);
                TcpConnection.close();
                runOnUiThread(() -> {
                    setButtonsEnabled(true);
                    showSnackbar("Connection Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void handleLoginResponse(NetworkPacket packet, String username, String password, boolean keepSignedIn) {
        try {
            JsonElement payload = packet.getPayload();

            if (payload.isJsonObject()) {
                User user = gson.fromJson(payload, User.class);

                if (user != null && user.getUsername() != null) {
                    TcpConnection.setCurrentUserId(user.getId());

                    SharedPreferences.Editor editor = preferences.edit();
                    if (keepSignedIn) {
                        editor.putString("username", username);
                        editor.putString("password", password);
                    } else {
                        editor.remove("username");
                        editor.remove("password");
                    }
                    editor.apply();

                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                }
            } else {
                String errorMsg = gson.fromJson(payload, String.class);
                showSnackbar(errorMsg);
                TcpConnection.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing login response", e);
            showSnackbar("Login processing error: " + e.getMessage());
        }
    }

    private void checkAutoLogin() {
        String savedUser = preferences.getString("username", null);
        String savedPass = preferences.getString("password", null);

        if (savedUser == null || savedPass == null) return;

        setButtonsEnabled(false);
        Toast.makeText(this, "Auto-Login...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                TcpConnection.close();
                TcpConnection.connect(config.getServerIp(), config.getServerPort());

                ChatDtos.AuthDto loginData = new ChatDtos.AuthDto(savedUser, savedPass);
                NetworkPacket request = new NetworkPacket(PacketType.LOGIN_REQUEST, 0, loginData);
                TcpConnection.sendPacket(request);

                NetworkPacket response = TcpConnection.readNextPacket();

                runOnUiThread(() -> {
                    if (response != null && response.getType() == PacketType.LOGIN_RESPONSE) {
                        try {
                            if (response.getPayload().isJsonObject()) {
                                User user = gson.fromJson(response.getPayload(), User.class);
                                TcpConnection.setCurrentUserId(user.getId());

                                Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                                startActivity(intent);
                                finish();
                                return;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to parse auto-login response", e);
                        }
                    }
                    handleAutoLoginFail();
                });
            } catch (Exception e) {
                Log.e(TAG, "Network error during auto-login", e);
                runOnUiThread(this::handleAutoLoginFail);
            }
        }).start();
    }

    private void handleAutoLoginFail() {
        TcpConnection.close();
        Toast.makeText(this, "Auto-login failed.", Toast.LENGTH_SHORT).show();
        setButtonsEnabled(true);
    }

    public void handleRegister(View view) {
        Intent newActivity = new Intent(LoginActivity.this, RegisterActivity.class);
        startActivity(newActivity);
    }

    private void setButtonsEnabled(boolean enabled) {
        findViewById(R.id.button).setEnabled(enabled);
        findViewById(R.id.button2).setEnabled(enabled);
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(Color.RED)
                .setTextColor(Color.WHITE)
                .show();
    }
}

