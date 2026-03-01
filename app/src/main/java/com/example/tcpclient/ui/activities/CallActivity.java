package com.example.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.example.tcpclient.network.VideoCallManager;
import com.example.tcpclient.utils.ClientKeyManager;
import com.example.tcpclient.R;
import com.example.tcpclient.network.VoiceCallManager;
import com.example.tcpclient.network.TcpConnection;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import javax.crypto.SecretKey;

import chat.network.NetworkPacket;
import chat.network.PacketType;

public class CallActivity extends AppCompatActivity {
    private VoiceCallManager voiceManager;
    private VideoCallManager videoManager;

    private DatagramSocket audioSocket;
    private DatagramSocket videoSocket;

    private static final int UDP_SERVER_AUDIO_PORT = 15556;
    private static final int UDP_SERVER_VIDEO_PORT = 15557;

    private int targetUserId;
    private int currentChatId;
    private String serverIp;
    private androidx.camera.lifecycle.ProcessCameraProvider cameraProvider;
    private volatile boolean isCallActive = true;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        targetUserId = getIntent().getIntExtra("TARGET_USER_ID", -1);
        currentChatId = getIntent().getIntExtra("CHAT_ID", -1);
        String username = getIntent().getStringExtra("USERNAME");
        serverIp = getIntent().getStringExtra("SERVER_IP");
        int myUserId = getIntent().getIntExtra("MY_USER_ID", -1);

        TextView txtName = findViewById(R.id.txtCallName);
        txtName.setText(username);

        startNetworkStack(myUserId, targetUserId);

        FloatingActionButton btnEndCall = findViewById(R.id.btnEndCall);
        btnEndCall.setOnClickListener(v -> hangUp());
    }

    private void startNetworkStack(int myId, int targetId) {
        new Thread(() -> {
            try {
                if (initUdpSockets()) {
                    startAudioReceiver();
                    startVideoReceiver();
                    startPeriodicHolePunch(myId, targetId);

                    boolean isAudioOnly = getIntent().getBooleanExtra("IS_AUDIO", true);
                    runOnUiThread(() -> {
                        initVoiceCall(myId);
                        if (!isAudioOnly) initVideoCall(myId);
                    });
                }
            } catch (Exception e) { Log.e("UDP", "Failed", e); }
        }).start();
    }

    private boolean initUdpSockets() {
        try {
            audioSocket = new DatagramSocket();
            videoSocket = new DatagramSocket();
            return true;
        } catch (Exception e) { return false; }
    }

    private void startPeriodicHolePunch(int myId, int targetId) {
        new Thread(() -> {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(8);
                buffer.putInt(myId);
                buffer.putInt(targetId);
                byte[] data = buffer.array();

                InetAddress serverAddr = InetAddress.getByName(serverIp);
                DatagramPacket audioPunch = new DatagramPacket(data, data.length, serverAddr, UDP_SERVER_AUDIO_PORT);
                DatagramPacket videoPunch = new DatagramPacket(data, data.length, serverAddr, UDP_SERVER_VIDEO_PORT);

                while (isCallActive) {
                    if (audioSocket != null && !audioSocket.isClosed()) audioSocket.send(audioPunch);
                    if (videoSocket != null && !videoSocket.isClosed()) videoSocket.send(videoPunch);
                    Thread.sleep(1000);
                }
            } catch (Exception e) {}
        }).start();
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
            if (packet.getType() == PacketType.CALL_END || packet.getType() == PacketType.CALL_DENY) {
                closeCallScreen();
            }
        });
    }

    private void closeCallScreen() {
        isCallActive = false;
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Exception e) {}
        }
        if (voiceManager != null) voiceManager.endCall();
        if (videoManager != null) videoManager.endVideo();
        finish();
    }

    private void initVoiceCall(int myUserId) {
        ClientKeyManager keyManager = new ClientKeyManager(this);
        SecretKey sessionKey = keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            voiceManager = new VoiceCallManager(this, serverIp, myUserId);
            voiceManager.startCall(targetUserId, sessionKey, audioSocket);
        } else { hangUp(); }
    }

//    private void initVideoCall(int myUserId) {
//        ClientKeyManager keyManager = new ClientKeyManager(this);
//        SecretKey sessionKey = keyManager.getKey(currentChatId);
//
//        if (sessionKey != null) {
//            findViewById(R.id.remoteVideo).setVisibility(android.view.View.VISIBLE);
//            findViewById(R.id.previewView).setVisibility(android.view.View.VISIBLE);
//            findViewById(R.id.cardAvatar).setVisibility(android.view.View.GONE);
//
//            android.view.SurfaceView remoteVideoView = findViewById(R.id.remoteVideo);
//
//            remoteVideoView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
//                @Override
//                public void surfaceCreated(android.view.SurfaceHolder holder) {
//                    try {
//                        // Initializam VideoCallManager cu suprafata remote
//                        videoManager = new VideoCallManager(serverIp, myUserId, holder.getSurface());
//                        videoManager.startVideo(targetUserId, sessionKey, videoSocket);
//                    } catch (Exception e) {
//                        Log.e("VIDEO", "VideoCallManager init failed", e);
//                        return;
//                    }
//
//                    // Pornim camera dupa 300ms sa fie siguri ca encoderul e pornit
//                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
//                        if (videoManager != null) {
//                            startCameraX();
//                        }
//                    }, 300);
//                }
//
//                @Override
//                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {}
//
//                @Override
//                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
//                    if (videoManager != null) videoManager.endVideo();
//                }
//            });
//        }
//    }

    private void initVideoCall(int myUserId) {
        ClientKeyManager keyManager = new ClientKeyManager(this);
        SecretKey sessionKey = keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            findViewById(R.id.remoteVideo).setVisibility(android.view.View.VISIBLE);
            findViewById(R.id.previewView).setVisibility(android.view.View.VISIBLE);
            findViewById(R.id.cardAvatar).setVisibility(android.view.View.GONE);

            android.view.TextureView remoteVideoView = findViewById(R.id.remoteVideo);
            remoteVideoView.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int w, int h) {
                    android.view.Surface s = new android.view.Surface(surface);
                    videoManager = new VideoCallManager(serverIp, myUserId, s);
                    videoManager.startVideo(targetUserId, sessionKey, videoSocket);

                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    matrix.postRotate(270, w / 2f, h / 2f);
                    float scaleY = (float) h / w;
                    matrix.postScale(1f, scaleY, w / 2f, h / 2f);
                    remoteVideoView.setTransform(matrix);

                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (videoManager != null) startCameraX();
                    }, 300);
                }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s) {}
            });
        }
    }

    private void hangUp() {
        TcpConnection.sendPacket(new NetworkPacket(PacketType.CALL_END, TcpConnection.getCurrentUserId(), targetUserId));
        closeCallScreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isCallActive = false;
        if (voiceManager != null) voiceManager.endCall();
        if (videoManager != null) videoManager.endVideo();
        if (audioSocket != null && !audioSocket.isClosed()) audioSocket.close();
        if (videoSocket != null && !videoSocket.isClosed()) videoSocket.close();
    }

    private void startAudioReceiver() {
        new Thread(() -> {
            byte[] buffer = new byte[8192];
            while (audioSocket != null && !audioSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    audioSocket.receive(packet);
                    if (packet.getLength() > 8) {
                        byte[] audioEncrypted = new byte[packet.getLength() - 8];
                        System.arraycopy(buffer, 8, audioEncrypted, 0, audioEncrypted.length);
                        if (voiceManager != null) voiceManager.receiveAudioData(audioEncrypted);
                    }
                } catch (Exception e) {}
            }
        }).start();
    }

    private void startVideoReceiver() {
        new Thread(() -> {
            byte[] buffer = new byte[65000];
            while (videoSocket != null && !videoSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    videoSocket.receive(packet);
                    if (packet.getLength() >= 17) {
                        byte[] videoData = new byte[packet.getLength()];
                        System.arraycopy(buffer, 0, videoData, 0, packet.getLength());
                        if (videoManager != null) videoManager.receiveVideoSlice(videoData);
                    }
                } catch (Exception e) {}
            }
        }).start();
    }

    private void startCameraX() {
        com.google.common.util.concurrent.ListenableFuture<androidx.camera.lifecycle.ProcessCameraProvider> cameraProviderFuture =
                androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this);

        Executor mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                int rotation = getWindowManager().getDefaultDisplay().getRotation();

                // Preview pentru a vedea camera locala
                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder()
                        .setTargetResolution(new android.util.Size(480, 640))
                        .setTargetRotation(rotation)
                        .build();
                preview.setSurfaceProvider(
                        ((androidx.camera.view.PreviewView) findViewById(R.id.previewView)).getSurfaceProvider()
                );

                // Use case care trimite frame-uri direct pe suprafata encoderului
                // Fara nicio conversie YUV - encoder-ul primeste date native de la camera
                androidx.camera.core.Preview encoderPreview = new androidx.camera.core.Preview.Builder()
                        .setTargetResolution(new android.util.Size(480, 640))
                        .setTargetRotation(rotation)
                        .build();

                android.view.Surface encoderSurface = videoManager.getEncoderSurface();
                if (encoderSurface != null) {
                    encoderPreview.setSurfaceProvider(request -> {
                        // Camera scrie direct pe suprafata encoderului
                        request.provideSurface(
                                encoderSurface,
                                mainExecutor,
                                result -> Log.d("CAMERA", "Encoder surface result: " + result.getResultCode())
                        );
                    });
                }

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        encoderPreview
                );

                Log.d("CAMERA", "CameraX pornit cu Surface mode!");
            } catch (Exception e) {
                Log.e("CAMERA", "CameraX failed", e);
            }
        }, mainExecutor);
    }
}

