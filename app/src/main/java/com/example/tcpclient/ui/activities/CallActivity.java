package com.example.tcpclient.ui.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
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

import javax.crypto.SecretKey;

import chat.network.NetworkPacket;
import chat.network.PacketType;

public class CallActivity extends AppCompatActivity {
    private VoiceCallManager voiceManager;
    private VideoCallManager videoManager;

    // ARHITECTURA NOUĂ: Două socket-uri separate!
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
                    // Pornim paznicii pe ambele porturi
                    startAudioReceiver();
                    startVideoReceiver();

                    // ÎNLOCUIT: Pornim bucla de Hole Punch periodic (Heartbeat)
                    startPeriodicHolePunch(myId, targetId);

                    boolean isAudioOnly = getIntent().getBooleanExtra("IS_AUDIO", true);
                    runOnUiThread(() -> {
                        initVoiceCall(myId);
                        if (!isAudioOnly) initVideoCall(myId);
                    });
                }
            } catch (Exception e) {
                Log.e("UDP", "Failed to start network stack", e);
            }
        }).start();
    }

    // CREEAZĂ CELE DOUĂ SOCKET-URI LORE
    private boolean initUdpSockets() {
        try {
            audioSocket = new DatagramSocket();
            videoSocket = new DatagramSocket();
            Log.d("UDP", "AudioSocket local port: " + audioSocket.getLocalPort());
            Log.d("UDP", "VideoSocket local port: " + videoSocket.getLocalPort());
            return true;
        } catch (Exception e) {
            Log.e("UDP", "Socket creation failed", e);
            return false;
        }
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

                // Trimitem un impuls la fiecare secundă ca să ținem porturile deschise!
                while (isCallActive) {
                    if (audioSocket != null && !audioSocket.isClosed()) audioSocket.send(audioPunch);
                    if (videoSocket != null && !videoSocket.isClosed()) videoSocket.send(videoPunch);
                    Thread.sleep(1000); // Pauză de 1 secundă între bătăi
                }
            } catch (Exception e) {
                Log.e("UDP", "Periodic Hole Punch error", e);
            }
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

    @SuppressLint("SetTextI18n")
    private void handlePacketOnUI(NetworkPacket packet) {
        runOnUiThread(() -> {
            if (packet.getType() == PacketType.CALL_END) {
                android.widget.Toast.makeText(this, "Call ended by partner.", android.widget.Toast.LENGTH_SHORT).show();
                closeCallScreen();
            }
            else if (packet.getType() == PacketType.CALL_DENY) {
                android.widget.Toast.makeText(this, "Call declined (Busy).", android.widget.Toast.LENGTH_SHORT).show();
                closeCallScreen();
            }
        });
    }

    private void closeCallScreen() {
        isCallActive = false;
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
                Log.d("UDP", "CameraX unbinded - suprafata e libera.");
            } catch (Exception e) { Log.e("UDP", "Error stopping camera", e); }
        }

        if (voiceManager != null) voiceManager.endCall();
        if (videoManager != null) videoManager.endVideo();
        finish();
    }

    @SuppressLint("SetTextI18n")
    private void initVoiceCall(int myUserId) {
        ClientKeyManager keyManager = new ClientKeyManager(this);
        SecretKey sessionKey = keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            voiceManager = new VoiceCallManager(this, serverIp, myUserId);
            // DĂM MAI DEPARTE DOAR AUDIO SOCKET!
            voiceManager.startCall(targetUserId, sessionKey, audioSocket);
        } else {
            hangUp();
        }
    }

    private void initVideoCall(int myUserId) {
        ClientKeyManager keyManager = new ClientKeyManager(this);
        SecretKey sessionKey = keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            findViewById(R.id.remoteVideo).setVisibility(android.view.View.VISIBLE);
            findViewById(R.id.previewView).setVisibility(android.view.View.VISIBLE);
            findViewById(R.id.cardAvatar).setVisibility(android.view.View.GONE);

            android.view.SurfaceView remoteVideoView = findViewById(R.id.remoteVideo);

            remoteVideoView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    try {
                        Log.d("UDP", "Surface-ul e gata de luptă!");
                        videoManager = new VideoCallManager(serverIp, myUserId, holder.getSurface());
                        videoManager.startVideo(targetUserId, sessionKey, videoSocket);
                    }catch (Exception e){
                        Log.e("UDP", "CRASH LA VIDEO MANAGER:", e);
                    }

                    // AM SCOS VERIFICAREA DE SURFACE ȘI AM PUS TIMPUL LA 500ms
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (videoManager != null) {
                            Log.d("UDP", "Dăm drumul la cameră coaie!");
                            startCameraX(null);
                        }
                    }, 500);
                }

                @Override
                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {}

                @Override
                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    if (videoManager != null) videoManager.endVideo();
                }
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

        // Curățăm ambele socketuri
        if (audioSocket != null && !audioSocket.isClosed()) audioSocket.close();
        if (videoSocket != null && !videoSocket.isClosed()) videoSocket.close();
        Log.d("UDP", "Ambele socket-uri închise cu succes.");
    }

    // THREAD 1: ASCULTĂ DOAR AUDIO (Simplificat, nu mai are if/else)
    private void startAudioReceiver() {
        new Thread(() -> {
            byte[] buffer = new byte[8192];
            while (audioSocket != null && !audioSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    audioSocket.receive(packet);

                    // Scoate direct datele (ignorăm myId și targetId de la server)
                    if(packet.getLength() > 8) {
                        byte[] audioEncrypted = new byte[packet.getLength() - 8];
                        System.arraycopy(buffer, 8, audioEncrypted, 0, audioEncrypted.length);
                        if (voiceManager != null) voiceManager.receiveAudioData(audioEncrypted);
                    }
                } catch (Exception e) { Log.e("UDP", "Audio Receiver error", e); }
            }
        }).start();
    }

    // THREAD 2: ASCULTĂ DOAR VIDEO
    private void startVideoReceiver() {
        new Thread(() -> {
            byte[] buffer = new byte[65000];
            while (videoSocket != null && !videoSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    videoSocket.receive(packet);

                    // Trimite tot către VideoManager (are decodorul lui de feliere)
                    if (packet.getLength() >= 17) {
                        byte[] videoData = new byte[packet.getLength()];
                        System.arraycopy(buffer, 0, videoData, 0, packet.getLength());
                        if (videoManager != null) videoManager.receiveVideoSlice(videoData);
                    }
                } catch (Exception e) { Log.e("UDP", "Video Receiver error", e); }
            }
        }).start();
    }

    private void startCameraX(android.view.Surface unused) {
        com.google.common.util.concurrent.ListenableFuture<androidx.camera.lifecycle.ProcessCameraProvider> cameraProviderFuture =
                androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder()
                        .setTargetResolution(new android.util.Size(480, 640)).build();
                preview.setSurfaceProvider(((androidx.camera.view.PreviewView) findViewById(R.id.previewView)).getSurfaceProvider());

                androidx.camera.core.ImageAnalysis imageAnalysis = new androidx.camera.core.ImageAnalysis.Builder()
                        // FOLOSEȘTE SETTARGETRESOLUTION MĂCAR LA 480x640 (portret) SAU 640x480 (landscape)
                        .setTargetResolution(new android.util.Size(480, 640)) // Telefoanele stau in portret!
                        .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                imageAnalysis.setAnalyzer(androidx.core.content.ContextCompat.getMainExecutor(this), image -> {
                    byte[] yuvBytes = imageToByteArray(image);
                    if (videoManager != null) {
                        videoManager.encodeFrame(yuvBytes);
                    }
                    image.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
            } catch (Exception e) { Log.e("CAMERA", "Failed", e); }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
    }

//    private byte[] imageToByteArray(androidx.camera.core.ImageProxy image) {
//        androidx.camera.core.ImageProxy.PlaneProxy[] planes = image.getPlanes();
//        ByteBuffer yBuffer = planes[0].getBuffer();
//        ByteBuffer uBuffer = planes[1].getBuffer();
//        ByteBuffer vBuffer = planes[2].getBuffer();
//
//        int ySize = yBuffer.remaining();
//        int uSize = uBuffer.remaining();
//        int vSize = vBuffer.remaining();
//
//        byte[] nv21 = new byte[ySize + uSize + vSize];
//        yBuffer.get(nv21, 0, ySize);
//        vBuffer.get(nv21, ySize, vSize);
//        uBuffer.get(nv21, ySize + vSize, uSize);
//        return nv21;
//    }

    private byte[] imageToByteArray(androidx.camera.core.ImageProxy image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;
        byte[] nv12 = new byte[ySize + uvSize * 2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Canal Alb-Negru
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // Canal Culoare 1
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // Canal Culoare 2

        // Aici aflăm cât de mare e marginea aia invizibilă adăugată de telefon
        int rowStrideY = image.getPlanes()[0].getRowStride();
        int rowStrideUV = image.getPlanes()[1].getRowStride();
        int pixelStrideUV = image.getPlanes()[1].getPixelStride();

        // 1. Extragem forma clară (Y)
        int pos = 0;
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * rowStrideY);
            yBuffer.get(nv12, pos, width);
            pos += width;
        }

        // 2. Extragem culorile și le intercalăm perfect
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int uvIndex = row * rowStrideUV + col * pixelStrideUV;
                nv12[pos++] = uBuffer.get(uvIndex);
                nv12[pos++] = vBuffer.get(uvIndex);
            }
        }
        return nv12;
    }
}
