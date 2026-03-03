package com.example.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;
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
import com.example.tcpclient.ui.adapters.MessageAdapter;
import com.example.tcpclient.R;
import com.example.tcpclient.network.TcpConnection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

import chat.models.Message;
import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import chat.security.CryptoHelper;


public class ConversationActivity extends AppCompatActivity {
    public volatile List<Message> messages = new ArrayList<>();
    RecyclerView recyclerView;
    MessageAdapter messageAdapter;
    private int currentChatId = -1;
    private final Gson gson = new Gson();
    private ClientKeyManager keyManager;
    private String chatName;
    private int targetUserId;
    private static final String TAG = "ConversationActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_conversation);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        View mainView = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            int imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(0, 0, 0, imeHeight);
            return insets;
        });

        TcpConnection.setContext(this);

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPress();
            }
        });

        keyManager = new ClientKeyManager(this);

        Intent intent = getIntent();
        this.chatName = intent.getStringExtra("CHAT_NAME");
        this.currentChatId = intent.getIntExtra("CHAT_ID", -1);

        this.targetUserId = intent.getIntExtra("TARGET_USER_ID", -1);

        TextView txtChatName = findViewById(R.id.txtChatName);
        if(chatName != null) txtChatName.setText(chatName);

        recyclerView = findViewById(R.id.recyclerViewMessages);
        messageAdapter = new MessageAdapter(this, messages, TcpConnection.getCurrentUserId(), this::handleLongMessageClick);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messageAdapter);

        View btnBack = findViewById(R.id.btnBackArrow);
        btnBack.setOnClickListener(v -> handleBackPress());

    }

    @Override
    protected void onResume() {
        super.onResume();
        TcpConnection.setPacketListener(this::handlePacketOnUI);
        sendEnterChatRequest();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TcpConnection.setPacketListener(null);
        sendExitChatRequest();
    }

    private void handlePacketOnUI(NetworkPacket packet) {
        runOnUiThread(() -> handlePacket(packet));
    }

    @SuppressLint("NotifyDataSetChanged")
    private void handlePacket(NetworkPacket packet) {
        try {
            SecretKey chatKey = keyManager.getKey(currentChatId);

            switch (packet.getType()) {
                case GET_MESSAGES_RESPONSE:
                    Type listType = new TypeToken<List<Message>>(){}.getType();
                    List<Message> history = gson.fromJson(packet.getPayload(), listType);

                    messages.clear();
                    if (history != null) {
                        for (Message m : history) {
                            try {
                                String decryptedText = CryptoHelper.unpackAndDecrypt(chatKey, m.getContent());
                                m.setContent(decryptedText.getBytes());
                            } catch (Exception e) {
                                Log.e(TAG, "Decryption failed for history message ID: " + m.getId());
                                m.setContent("[Decryption Error]".getBytes());
                            }
                        }
                        messages.addAll(history);
                    }
                    messageAdapter.notifyDataSetChanged();
                    scrollToBottom();
                    break;

                case RECEIVE_MESSAGE:
                    Message msg = gson.fromJson(packet.getPayload(), Message.class);
                    if (msg != null) {
                        try {
                            String decryptedText = CryptoHelper.unpackAndDecrypt(chatKey, msg.getContent());
                            msg.setContent(decryptedText.getBytes());
                        } catch (Exception e) {
                            Log.e(TAG, "Decryption failed for new incoming message");
                            msg.setContent("[Decryption Error]".getBytes());
                        }
                        messages.add(msg);
                        messageAdapter.notifyItemInserted(messages.size() - 1);
                        scrollToBottom();
                    }
                    break;

                case EDIT_MESSAGE_BROADCAST:
                    ChatDtos.EditMessageDto editDto = gson.fromJson(packet.getPayload(), ChatDtos.EditMessageDto.class);
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).getId() == editDto.messageId) {

                            try {
                                String decryptedEdit = CryptoHelper.unpackAndDecrypt(chatKey, editDto.newContent);
                                messages.get(i).setContent(decryptedEdit.getBytes());

                            } catch (Exception e) {
                                Log.e(TAG, "Failed to decrypt edited message", e);
                                messages.get(i).setContent("[Decryption Error on Edit]".getBytes());
                            }

                            messageAdapter.notifyItemChanged(i);
                            break;
                        }
                    }
                    break;

                case DELETE_MESSAGE_BROADCAST:
                    int deletedId = gson.fromJson(packet.getPayload(), Integer.class);
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).getId() == deletedId) {
                            messages.remove(i);
                            messageAdapter.notifyItemRemoved(i);
                            break;
                        }
                    }
                    break;

                case GET_CHAT_MEMBERS_RESPONSE:
                    Type idListType = new TypeToken<List<Integer>>(){}.getType();
                    List<Integer> memberIds = gson.fromJson(packet.getPayload(), idListType);

                    if (memberIds != null) {
                        int myId = TcpConnection.getCurrentUserId();
                        for (Integer uid : memberIds) {
                            if (uid != myId) {
                                this.targetUserId = uid;
                                Log.i(TAG, "Partner ID retrieved for calls: " + targetUserId);
                                break;
                            }
                        }
                    }
                    break;

                case EXIT_CHAT_RESPONSE:
                    finish();
                    break;

                case ENTER_CHAT_RESPONSE: break;
            }
        } catch (Exception e) {
            Log.e(TAG, "General error in handlePacket", e);
        }
    }

    public void handleMessage(View view) {
        EditText messageBox = findViewById(R.id.editTextMessage);
        String text = messageBox.getText().toString().trim();

        if (text.isEmpty()) return;

        try {
            SecretKey chatKey = keyManager.getKey(currentChatId);
            if (chatKey == null) {
                Toast.makeText(this, "Key missing! Handshake incomplete.", Toast.LENGTH_SHORT).show();
                return;
            }

            byte[] encryptedData = CryptoHelper.encryptAndPack(chatKey, text);

            Message msg = new Message(0, encryptedData,0, TcpConnection.getCurrentUserId(), currentChatId);
            NetworkPacket packet = new NetworkPacket(PacketType.SEND_MESSAGE, TcpConnection.getCurrentUserId(), msg);
            TcpConnection.sendPacket(packet);

            messageBox.setText("");
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed for outgoing message", e);
            Toast.makeText(this, "Encryption Error!", Toast.LENGTH_SHORT).show();
        }
    }

    private void performEdit(int messageId, String newText) {
        try {
            SecretKey chatKey = keyManager.getKey(currentChatId);
            byte[] encryptedNewContent = CryptoHelper.encryptAndPack(chatKey, newText);

            ChatDtos.EditMessageDto dto = new ChatDtos.EditMessageDto(messageId, encryptedNewContent);
            NetworkPacket packet = new NetworkPacket(PacketType.EDIT_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), dto);
            TcpConnection.sendPacket(packet);
        } catch (Exception e) {
            Log.e(TAG, "Failed to encrypt edit request", e);
            Toast.makeText(this, "Edit Error!", Toast.LENGTH_SHORT).show();
        }
    }

    private void performDelete(int messageId) {
        NetworkPacket packet = new NetworkPacket(PacketType.DELETE_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), messageId);
        TcpConnection.sendPacket(packet);
    }

    private void sendEnterChatRequest() {
        if (currentChatId != -1) {
            NetworkPacket packet = new NetworkPacket(PacketType.ENTER_CHAT_REQUEST, TcpConnection.getCurrentUserId(), currentChatId);
            TcpConnection.sendPacket(packet);

            NetworkPacket membersReq = new NetworkPacket(PacketType.GET_CHAT_MEMBERS_REQUEST, TcpConnection.getCurrentUserId(), currentChatId);
            TcpConnection.sendPacket(membersReq);
        }
    }

    private void sendExitChatRequest() {
        NetworkPacket packet = new NetworkPacket(PacketType.EXIT_CHAT_REQUEST, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(packet);
    }

    public void handleBackPress() {
        finish();
    }

    private void scrollToBottom() {
        if (!messages.isEmpty()) {
            recyclerView.post(() -> recyclerView.smoothScrollToPosition(messages.size() - 1));
        }
    }

    private ArrayAdapter<String> createDialogAdapter(String[] options) {
        return new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, options) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setTextColor(Color.WHITE);
                return view;
            }
        };
    }

    public void handleLongMessageClick(Message message) {
        if (message.getSenderId() != TcpConnection.getCurrentUserId()) return;

        android.text.SpannableString btnCancel = new android.text.SpannableString("Cancel");
        btnCancel.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#137fec")), 0, btnCancel.length(), 0);

        String[] options = {"Modify", "Delete"};
        ArrayAdapter<String> adapter = createDialogAdapter(options);

        new AlertDialog.Builder(ConversationActivity.this, R.style.DialogSmecher)
                .setTitle("Options")
                .setAdapter(adapter, (dialog, which) -> {
                    if (which == 0) modifyMessage(message);
                    else deleteMessage(message);
                })
                .setNegativeButton(btnCancel, (dialog, which) -> dialog.cancel())
                .show();
    }

    public void modifyMessage(Message message) {
        EditText input = new EditText(this);
        String currentContent = new String(message.getContent());
        input.setTextColor(Color.WHITE);
        input.setText(currentContent);
        input.setSelection(currentContent.length());

        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Modify message")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String newText = input.getText().toString().trim();
                    if (!newText.isEmpty()) performEdit(message.getId(), newText);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void deleteMessage(Message message) {
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Delete Message")
                .setMessage("Are you sure?")
                .setPositiveButton("DELETE", (dialog, which) -> performDelete(message.getId()))
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void handleAudioCallClick(View view) {
        initiateCall(true);
    }

    public void handleVideoCallClick(View view) {
        initiateCall(false);
    }

    private void initiateCall(boolean isAudioCall) {
        if (targetUserId == -1) {
            Toast.makeText(this, "Error: Partner user unknown", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(ConversationActivity.this, CallActivity.class);
        intent.putExtra("TARGET_USER_ID", targetUserId);
        intent.putExtra("CHAT_ID", currentChatId);
        intent.putExtra("USERNAME", chatName);
        intent.putExtra("MY_USER_ID", TcpConnection.getCurrentUserId());

        intent.putExtra("IS_AUDIO", isAudioCall);

        try {
            String serverIp = TcpConnection.getSocket().getInetAddress().getHostAddress();
            intent.putExtra("SERVER_IP", serverIp);
        } catch (Exception e) {
            ConfigReader configReader = new ConfigReader(this);
            intent.putExtra("SERVER_IP", configReader.getServerIp());
            Toast.makeText(this, "Server IP retrieval error!", Toast.LENGTH_SHORT).show();
        }

        ChatDtos.CallRequestDto callDto = new ChatDtos.CallRequestDto(targetUserId, currentChatId, isAudioCall);
        NetworkPacket callRequest = new NetworkPacket(PacketType.CALL_REQUEST, TcpConnection.getCurrentUserId(), callDto);
        TcpConnection.sendPacket(callRequest);

        startActivity(intent);
    }
}
