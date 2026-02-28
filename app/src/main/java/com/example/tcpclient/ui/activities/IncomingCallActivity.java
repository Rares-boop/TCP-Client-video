package com.example.tcpclient.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.example.tcpclient.R;
import com.example.tcpclient.network.TcpConnection;

import chat.network.NetworkPacket;
import chat.network.PacketType;


public class IncomingCallActivity extends AppCompatActivity {
    private Ringtone ringtone;
    private Vibrator vibrator;
    private int callerId;
    private String callerName;
    private int chatId;
    private String serverIp;
    private boolean isAudio;
    private static final String TAG = "IncomingCallActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        callerId = getIntent().getIntExtra("CALLER_ID", -1);
        callerName = getIntent().getStringExtra("CALLER_NAME");
        chatId = getIntent().getIntExtra("CHAT_ID", -1);
        serverIp = getIntent().getStringExtra("SERVER_IP");
        isAudio = getIntent().getBooleanExtra("IS_AUDIO", true);

        TextView txtName = findViewById(R.id.txtCallerName);
        txtName.setText(callerName != null ? callerName : "Unknown Caller");

        startRinging();

        findViewById(R.id.btnAnswer).setOnClickListener(v -> answerCall());
        findViewById(R.id.btnDecline).setOnClickListener(v -> declineCall());
    }

    @Override
    protected void onResume() {
        super.onResume();
        TcpConnection.setPacketListener(this::handlePacketOnUI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TcpConnection.setPacketListener(null);
    }

    private void handlePacketOnUI(NetworkPacket packet) {
        runOnUiThread(() -> {
            if (packet.getType() == PacketType.CALL_END) {
                android.widget.Toast.makeText(this, "Call cancelled by caller.", android.widget.Toast.LENGTH_SHORT).show();

                stopRinging();
                finish();
            }
        });
    }
    private void startRinging() {
        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), notification);
            ringtone.play();

            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                long[] pattern = {0, 1000, 1000};
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ringtone or vibrator", e);
        }
    }

    private void stopRinging() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    private void answerCall() {
        stopRinging();
        sendTcpResponse(PacketType.CALL_ACCEPT);

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("TARGET_USER_ID", callerId);
        intent.putExtra("CHAT_ID", chatId);
        intent.putExtra("USERNAME", callerName);
        intent.putExtra("SERVER_IP", serverIp);
        intent.putExtra("MY_USER_ID", TcpConnection.getCurrentUserId());
        intent.putExtra("IS_AUDIO", isAudio);

        startActivity(intent);
        finish();
    }

    private void declineCall() {
        stopRinging();
        sendTcpResponse(PacketType.CALL_DENY);

        finish();
    }

    private void sendTcpResponse(PacketType type) {
        TcpConnection.sendPacket(
                new NetworkPacket(type, TcpConnection.getCurrentUserId(), callerId)
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
    }
}
