package com.example.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tcpclient.utils.ClientKeyManager;
import com.example.tcpclient.utils.ConfigReader;
import com.example.tcpclient.ui.adapters.ConversationAdapter;
import com.example.tcpclient.storage.LocalStorage;
import com.example.tcpclient.R;
import com.example.tcpclient.storage.SecureStorage;
import com.example.tcpclient.network.TcpConnection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import chat.models.GroupChat;
import chat.models.User;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import chat.security.CryptoHelper;


public class MainActivity extends AppCompatActivity {
    RecyclerView recyclerView;
    ConversationAdapter adapter;
    private final Gson gson = new Gson();
    AlertDialog dialog;
    private Spinner pendingSpinner;
    private List<String> pendingRawUsers;
    private int pendingChatTargetId = -1;
    private String pendingChatName = null;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginLayout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        TcpConnection.setContext(this);

        String[] requiredPermissions = {
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA
        };

        boolean needsPermissions = false;
        for (String permission : requiredPermissions) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needsPermissions = true;
                break;
            }
        }

        if (needsPermissions) {
            androidx.core.app.ActivityCompat.requestPermissions(this, requiredPermissions, 100);
        }

        recyclerView = findViewById(R.id.recyclerViewConversations);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ConversationAdapter(
                this,
                LocalStorage.getCurrentUserGroupChats(),
                this::handleChatClick,
                this::handleLongChatClick
        );
        recyclerView.setAdapter(adapter);

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dialog != null && dialog.isShowing()) {
                    Toast.makeText(MainActivity.this, "Please finish the current action before exiting!", Toast.LENGTH_SHORT).show();
                } else {
                    handleLogout();
                }
            }
        });

        TcpConnection.startReading();
    }

    @Override
    protected void onResume() {
        super.onResume();

        Socket socket = TcpConnection.socket;
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            attemptAutoReconnect();
        } else {
            TcpConnection.setPacketListener(this::handlePacketOnUI);
            refreshConversations();
        }
    }

    private void handlePacketOnUI(NetworkPacket packet) {
        runOnUiThread(() -> handlePacket(packet));
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handlePacket(NetworkPacket packet) {
        switch (packet.getType()) {
            case GET_CHATS_RESPONSE:
                try {
                    Type listType = new TypeToken<List<GroupChat>>(){}.getType();
                    List<GroupChat> groupChats = gson.fromJson(packet.getPayload(), listType);

                    if (groupChats == null) groupChats = new ArrayList<>();

                    LocalStorage.setCurrentUserGroupChats(groupChats);
                    adapter.setGroupChats(groupChats);
                    adapter.notifyDataSetChanged();
                } catch (Exception e) { Log.e(TAG, "Failed to parse chat list from server.", e); }
                break;

            case GET_USERS_RESPONSE:
                try {
                    Type userListType = new TypeToken<List<String>>(){}.getType();
                    List<String> serverList = gson.fromJson(packet.getPayload(), userListType);
                    updateSpinnerData(serverList);
                } catch (Exception e) { Log.e(TAG, "Failed to parse users list.", e); }
                break;

            case GET_BUNDLE_RESPONSE:
                try {
                    ChatDtos.GetBundleResponseDto bundle = gson.fromJson(packet.getPayload(), ChatDtos.GetBundleResponseDto.class);
                    if (bundle.targetUserId != pendingChatTargetId) break;

                    java.security.PublicKey bobIdentityKey = CryptoHelper.stringToDilithiumPublic(bundle.identityKeyPublic);
                    java.security.PublicKey bobPreKey = CryptoHelper.stringToKyberPublic(bundle.signedPreKeyPublic);
                    byte[] bobSignature = android.util.Base64.decode(bundle.signature, android.util.Base64.NO_WRAP);

                    boolean isSigValid = CryptoHelper.verifySignature(bobIdentityKey, bobPreKey.getEncoded(), bobSignature);
                    if (!isSigValid) {
                        Toast.makeText(this, "SECURITY ALERT: Invalid signature!", Toast.LENGTH_LONG).show();
                        Log.e(TAG, "SECURITY ALERT: Dilithium signature validation failed for target ID: " + pendingChatTargetId);
                        return;
                    }

                    CryptoHelper.KEMResult kemResult = CryptoHelper.encapsulate(bobPreKey);
                    String ciphertextBase64 = android.util.Base64.encodeToString(kemResult.wrappedKey, android.util.Base64.NO_WRAP);

                    LocalStorage.pendingSecretKey = android.util.Base64.encodeToString(kemResult.aesKey.getEncoded(), android.util.Base64.NO_WRAP);

                    ChatDtos.CreateGroupDto createDto = new ChatDtos.CreateGroupDto(pendingChatTargetId, pendingChatName, ciphertextBase64);
                    NetworkPacket createReq = new NetworkPacket(PacketType.CREATE_CHAT_REQUEST, TcpConnection.getCurrentUserId(), createDto);
                    TcpConnection.sendPacket(createReq);

                } catch (Exception e) {
                    Log.e(TAG, "Critical Crypto error while processing Post-Quantum bundle.", e);
                    Toast.makeText(this, "Crypto Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                break;

            case CREATE_CHAT_BROADCAST:
                ChatDtos.NewChatBroadcastDto broadcastDto = gson.fromJson(packet.getPayload(), ChatDtos.NewChatBroadcastDto.class);
                GroupChat newChat = broadcastDto.groupInfo;

                if (adapter != null) adapter.setEnabled(true);
                if (dialog != null && dialog.isShowing()) dialog.dismiss();

                if (newChat.getId() <= 0) break;

                boolean chatExistsInUI = false;
                List<GroupChat> currentListUi = LocalStorage.getCurrentUserGroupChats();

                for (GroupChat existing : currentListUi) {
                    if (existing.getId() == newChat.getId()) {
                        chatExistsInUI = true;
                        break;
                    }
                }

                if (!chatExistsInUI) {
                    LocalStorage.getCurrentUserGroupChats().add(0, newChat);
                    adapter.setGroupChats(LocalStorage.getCurrentUserGroupChats());
                    adapter.notifyItemInserted(0);
                    recyclerView.scrollToPosition(0);
                    Log.i(TAG, "[UI] New chat successfully added: " + newChat.getName());
                } else {
                    Log.w(TAG, "[UI] Chat already exists in UI. Ignoring visual add.");
                }

                ClientKeyManager keyMgr = new ClientKeyManager(this);

                if (broadcastDto.keyCiphertext != null && !broadcastDto.keyCiphertext.isEmpty()) {
                    try {
                        byte[] cipherBytes = android.util.Base64.decode(broadcastDto.keyCiphertext, android.util.Base64.NO_WRAP);

                        String myPrivStr = keyMgr.getMyPreKeyPrivateKey();
                        java.security.PrivateKey myPriv = CryptoHelper.stringToKyberPrivate(myPrivStr);

                        // Decapsulate
                        javax.crypto.SecretKey shared = CryptoHelper.decapsulate(myPriv, cipherBytes);
                        String keyBase64 = android.util.Base64.encodeToString(shared.getEncoded(), android.util.Base64.NO_WRAP);

                        keyMgr.saveKey(newChat.getId(), keyBase64);
                        Log.i(TAG, "[BOB] Post-Quantum Key saved successfully!");
                    } catch (Exception e) { Log.e(TAG, "Error generating new chat or saving cryptographic key.", e); }
                }
                else if (LocalStorage.pendingSecretKey != null) {
                    keyMgr.saveKey(newChat.getId(), LocalStorage.pendingSecretKey);
                    LocalStorage.pendingSecretKey = null;
                    Log.i(TAG, "[ALICE] Key saved successfully from pending state!");
                }
                break;

            case RENAME_CHAT_BROADCAST:
                ChatDtos.RenameGroupDto renameDto = gson.fromJson(packet.getPayload(), ChatDtos.RenameGroupDto.class);

                List<GroupChat> currentList = LocalStorage.getCurrentUserGroupChats();
                for (int i = 0; i < currentList.size(); i++) {
                    if (currentList.get(i).getId() == renameDto.chatId) {
                        currentList.get(i).setName(renameDto.newName);
                        adapter.notifyItemChanged(i);
                        break;
                    }
                }
                break;

            case DELETE_CHAT_BROADCAST:
                int deletedId = gson.fromJson(packet.getPayload(), Integer.class);

                List<GroupChat> deleteList = LocalStorage.getCurrentUserGroupChats();
                for (int i = 0; i < deleteList.size(); i++) {
                    if (deleteList.get(i).getId() == deletedId) {
                        deleteList.remove(i);
                        adapter.notifyItemRemoved(i);
                        break;
                    }
                }
                break;
        }
    }

    private void refreshConversations() {
        NetworkPacket req = new NetworkPacket(PacketType.GET_CHATS_REQUEST, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(req);
    }

    private void performRename(GroupChat chat, String newName) {
        ChatDtos.RenameGroupDto dto = new ChatDtos.RenameGroupDto(chat.getId(), newName);
        NetworkPacket packet = new NetworkPacket(PacketType.RENAME_CHAT_REQUEST, TcpConnection.getCurrentUserId(), dto);
        TcpConnection.sendPacket(packet);
    }

    private void performDelete(GroupChat chat) {
        NetworkPacket packet = new NetworkPacket(PacketType.DELETE_CHAT_REQUEST, TcpConnection.getCurrentUserId(), chat.getId());
        TcpConnection.sendPacket(packet);
    }

    private void performLogout() {
        NetworkPacket p = new NetworkPacket(PacketType.LOGOUT, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(p);

        new android.os.Handler().postDelayed(() -> {
            TcpConnection.stopReading();
            TcpConnection.close();
            runOnUiThread(() -> {
                SharedPreferences prefs = SecureStorage.getEncryptedPrefs(MainActivity.this);
                prefs.edit().clear().apply();
                goToLogin();
            });
        }, 300);
    }

    private void loadUsersForSpinner(Spinner spinner, List<String> rawUserStrings) {
        this.pendingSpinner = spinner;
        this.pendingRawUsers = rawUserStrings;
        NetworkPacket req = new NetworkPacket(PacketType.GET_USERS_REQUEST, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(req);
    }

    private void updateSpinnerData(List<String> serverList) {
        if (pendingSpinner == null || pendingRawUsers == null) return;
        pendingRawUsers.clear();
        pendingRawUsers.addAll(serverList);
        List<String> displayNames = new ArrayList<>();
        for (String s : serverList) {
            String[] parts = s.split(",");
            if (parts.length > 1) displayNames.add(parts[1]);
            else displayNames.add(s);
        }
        setupSpinner(pendingSpinner, displayNames.toArray(new String[0]));
    }

    public void handleChatClick(GroupChat chat) {
        Intent intent = new Intent(this, ConversationActivity.class);
        intent.putExtra("CHAT_ID", chat.getId());
        intent.putExtra("CHAT_NAME", chat.getName());

        startActivity(intent);
    }

    public void handleLongChatClick(GroupChat chat) {
        String[] options = {"Rename ", "Delete "};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.dialog_option_item, options);
        new AlertDialog.Builder(MainActivity.this, R.style.DialogSmecher)
                .setTitle(chat.getName())
                .setAdapter(adapter, (dialog, which) -> {
                    if (which == 0) renameChat(chat);
                    else deleteChat(chat);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void renameChat(GroupChat chat) {
        EditText input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setHint("Enter the new name ");
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Rename " + chat.getName())
                .setView(input)
                .setPositiveButton("Save ", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) performRename(chat, newName);
                })
                .setNegativeButton("Cancel ", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void deleteChat(GroupChat chat) {
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Delete chat ")
                .setMessage("Are you sure you want to delete \"" + chat.getName() + "\"?")
                .setPositiveButton("Delete ", (dialog, which) -> performDelete(chat))
                .setNegativeButton("Cancel ", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void handleAddConversation(View view) {
        adapter.setEnabled(false);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_with_spinner, null);
        EditText editGroupName = dialogView.findViewById(R.id.editGroupName);
        Spinner spinner = dialogView.findViewById(R.id.mySpinner);
        editGroupName.setTextColor(Color.WHITE);
        editGroupName.setHintTextColor(Color.LTGRAY);
        setupSpinner(spinner, new String[]{"Loading...", "Please wait"});
        List<String> rawUserStrings = new ArrayList<>();

        dialog = new AlertDialog.Builder(MainActivity.this, R.style.DialogSmecher)
                .setTitle("Add a new conversation")
                .setView(dialogView)
                .setNegativeButton("Cancel", (d, w) -> { adapter.setEnabled(true); d.cancel(); })
                .setPositiveButton("OK", (d, w) -> {
                    String groupName = editGroupName.getText().toString().trim();
                    if (groupName.isEmpty()) { Toast.makeText(this, "Enter a group name!", Toast.LENGTH_SHORT).show(); adapter.setEnabled(true); return; }
                    int index = spinner.getSelectedItemPosition();
                    if (index < 0 || index >= rawUserStrings.size()) { Toast.makeText(this, "No user selected!", Toast.LENGTH_SHORT).show(); adapter.setEnabled(true); return; }
                    String selectedRaw = rawUserStrings.get(index);
                    int targetId = Integer.parseInt(selectedRaw.split(",")[0]);
                    this.pendingChatTargetId = targetId;
                    this.pendingChatName = groupName;

                    Toast.makeText(MainActivity.this, "Handshake: Requesting keys...", Toast.LENGTH_SHORT).show();

                    NetworkPacket packet = new NetworkPacket(PacketType.GET_BUNDLE_REQUEST, TcpConnection.getCurrentUserId(), new ChatDtos.GetBundleRequestDto(targetId));
                    TcpConnection.sendPacket(packet);
                }).create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        loadUsersForSpinner(spinner, rawUserStrings);
    }

    private void setupSpinner(Spinner spinner, String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items) {
            @NonNull
            @Override public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                android.widget.TextView view = (android.widget.TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                android.widget.TextView view = (android.widget.TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setBackgroundColor(Color.parseColor("#1c2630"));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    public void handleLogout() {
        if (adapter != null) adapter.setEnabled(false);
        AlertDialog d = new AlertDialog.Builder(MainActivity.this, R.style.DialogSmecher)
                .setTitle("Do you wish to logout ")
                .setNegativeButton("NO ", (dialog, which) -> { if (adapter != null) adapter.setEnabled(true); dialog.cancel(); })
                .setPositiveButton("YES ", (dialog, which) -> performLogout()).create();
        d.setCancelable(false);
        d.show();
    }

    private void goToLogin() {
        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void attemptAutoReconnect() {
        SharedPreferences preferences = SecureStorage.getEncryptedPrefs(getApplicationContext());
        String savedUser = preferences.getString("username", null);
        String savedPassword = preferences.getString("password", null);
        if (savedUser == null || savedPassword == null) { goToLogin(); return; }

        new Thread(() -> {
            try {
                ConfigReader configReader = new ConfigReader(this);
                TcpConnection.connect(configReader.getServerIp(), configReader.getServerPort());

                ChatDtos.AuthDto authDto = new ChatDtos.AuthDto(savedUser, savedPassword);
                NetworkPacket req = new NetworkPacket(PacketType.LOGIN_REQUEST, 0, authDto);
                TcpConnection.sendPacket(req);

                NetworkPacket resp = TcpConnection.readNextPacket();

                if (resp != null && resp.getType() == PacketType.LOGIN_RESPONSE) {
                    User user = gson.fromJson(resp.getPayload(), User.class);
                    if (user != null) {
                        TcpConnection.setCurrentUserId(user.getId());
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Auto-reconnected!", Toast.LENGTH_SHORT).show();

                            TcpConnection.startReading();
                            TcpConnection.setPacketListener(this::handlePacketOnUI);
                            refreshConversations();
                        });
                    } else runOnUiThread(this::goToLogin);
                } else runOnUiThread(this::goToLogin);

            } catch (Exception e) {
                Log.e(TAG, "Critical error during auto-reconnect sequence.", e);
                runOnUiThread(this::goToLogin);
            }
        }).start();
    }
}
