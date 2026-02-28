package com.example.tcpclient.network;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.tcpclient.utils.UdpCryptoUtils;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import javax.crypto.SecretKey;

public class VoiceCallManager {
    private static final int SERVER_PORT = 15556;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private boolean isCallActive = false;
    private final String serverIp;
    private final int myUserId;
    private int targetUserId;
    private SecretKey ramSessionKey;
    private DatagramSocket audioSocket;
    private final Context context;
    private static final String TAG = "VoiceCallManager";
    private AudioTrack speaker;

    public VoiceCallManager(Context context, String serverIp, int myUserId) {
        this.context = context;
        this.serverIp = serverIp;
        this.myUserId = myUserId;
    }

    public void startCall(int targetUserId, SecretKey sessionKey, DatagramSocket sharedAudioSocket) {
        if (isCallActive) return;

        this.targetUserId = targetUserId;
        this.ramSessionKey = sessionKey;
        this.audioSocket = sharedAudioSocket;
        this.isCallActive = true;

        setupSpeaker();
        startSending();
        Log.d(TAG, "Voice Manager pornit pe socket-ul primit!");
    }

    private void setupSpeaker() {
        try {
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
            speaker = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, minBuf, AudioTrack.MODE_STREAM);
            speaker.play();
        } catch (Exception e) {
            Log.e(TAG, "Speaker setup error: " + e.getMessage());
        }
    }

    public void receiveAudioData(byte[] encryptedData) {
        if (!isCallActive || speaker == null) return;
        try {
            byte[] pcmAudio = UdpCryptoUtils.decrypt(ramSessionKey, encryptedData);
            if (pcmAudio != null) {
                speaker.write(pcmAudio, 0, pcmAudio.length);
            } else {
                Log.e(TAG, "Decryption failed! Gunoi pe rețea ignorat.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Speaker error: " + e.getMessage());
        }
    }

    private void startSending() {
        new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
            byte[] audioBuffer = new byte[640];
            AudioRecord recorder = null;

            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Microphone permission NOT GRANTED!");
                    return;
                }

                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, minBuf);
                recorder.startRecording();

                InetAddress serverAddress = InetAddress.getByName(serverIp);

                while (isCallActive) {
                    int read = recorder.read(audioBuffer, 0, audioBuffer.length);
                    if (read > 0) {
                        byte[] encryptedAudio = UdpCryptoUtils.encrypt(ramSessionKey, audioBuffer);

                        if (encryptedAudio != null && audioSocket != null && !audioSocket.isClosed()) {
                            ByteBuffer packetBuf = ByteBuffer.allocate(8 + encryptedAudio.length);
                            packetBuf.putInt(myUserId);
                            packetBuf.putInt(targetUserId);
                            packetBuf.put(encryptedAudio);

                            byte[] packetData = packetBuf.array();
                            DatagramPacket p = new DatagramPacket(packetData, packetData.length, serverAddress, SERVER_PORT);
                            audioSocket.send(p);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Microphone recording error: " + e.getMessage());
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); recorder.release(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    public void endCall() {
        if (!isCallActive) return;
        isCallActive = false;

        audioSocket = null;
        ramSessionKey = null;

        if (speaker != null) {
            try { speaker.stop(); speaker.release(); } catch (Exception ignored) {}
            speaker = null;
        }
        Log.d(TAG, "Call ended cleanly in Voice Manager.");
    }
}
