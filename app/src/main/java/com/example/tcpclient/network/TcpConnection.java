package com.example.tcpclient.network;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import com.example.tcpclient.utils.ConfigReader;
import com.example.tcpclient.ui.activities.IncomingCallActivity;
import com.google.gson.Gson;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Security;

import javax.crypto.SecretKey;

import chat.network.ChatDtos;
import chat.network.NetworkPacket;
import chat.network.PacketType;
import chat.security.CryptoHelper;


public class TcpConnection {
    public static Socket socket;
    private static int currentUserId;
    private static SecretKey sessionKey = null;
    private static Context appContext;
    private static final String TAG = "TcpConnection";
    private static final Object writeLock = new Object();

    static {
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }
    public interface PacketListener {
        void onPacketReceived(NetworkPacket packet);
    }
    private static PacketListener currentListener;
    private static Thread readingThread;
    private static volatile boolean isReading = false;

    public static void setPacketListener(PacketListener listener) {
        currentListener = listener;
        Log.d(TAG, "Packet listener set: " + (listener == null ? "NULL" : listener.getClass().getSimpleName()));
    }

    public static void setContext(Context context) {
        appContext = context.getApplicationContext();
    }

    public static void startReading() {
        if (isReading) return;
        isReading = true;

        readingThread = new Thread(() -> {
            Log.d(TAG, "Network reading thread STARTED.");
            try {
                while (isReading && socket != null && !socket.isClosed()) {
                    NetworkPacket packet = readNextPacket();

                    if (packet == null) {
                        Log.e(TAG, "Received NULL packet. Connection is likely dead.");
                        close();
                        break;
                    }

                    if (packet.getType() == PacketType.CALL_REQUEST) {
                        handleIncomingCall(packet);
                        continue;
                    }

                    if (currentListener != null) {
                        currentListener.onPacketReceived(packet);
                    } else {
                        Log.w(TAG, "Packet ignored (no active listener for type): " + packet.getType());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Critical error in reading thread", e);
                close();
            }
        });
        readingThread.start();
    }
    private static void handleIncomingCall(NetworkPacket packet) {
        if (appContext == null) return;

        int callerId = packet.getSenderId();
        String callerName = "User " + callerId;

        ChatDtos.CallRequestDto incomingDto = new Gson().fromJson(packet.getPayload(), ChatDtos.CallRequestDto.class);
        int foundChatId = incomingDto.chatId;

        Log.d(TAG, "Incoming Call from " + callerId + ". Chat ID found: " + foundChatId);

        String serverIp = "127.0.0.1";
        try {
            if (socket != null) {
                serverIp = socket.getInetAddress().getHostAddress();
            }
            else{
                ConfigReader configReader = new ConfigReader(appContext);
                serverIp = configReader.getServerIp();
            }
        } catch (Exception e) {Log.w(TAG, "Failed to resolve server IP for call UI.");}

        Intent intent = new Intent(appContext, IncomingCallActivity.class);

        intent.putExtra("CALLER_ID", callerId);
        intent.putExtra("CALLER_NAME", callerName);
        intent.putExtra("CHAT_ID", foundChatId);
        intent.putExtra("SERVER_IP", serverIp);

        intent.putExtra("IS_AUDIO", incomingDto.isAudio);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appContext.startActivity(intent);
    }

    public static void stopReading() {
        isReading = false;
        if(readingThread != null){
            readingThread.interrupt();
        }
    }

    public static void connect(String host, int port) throws Exception {
        socket = new Socket(host, port);

        socket.setTcpNoDelay(true);

        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        if (!performHandshake()) {
            close();
            throw new Exception("Handshake with server failed!");
        }
    }
    private static PrintWriter out;
    private static BufferedReader in;
    private static boolean performHandshake() {
        try {
            Log.d(TAG, "Starting secure handshake...");

            String jsonHello = in.readLine();
            if(jsonHello==null){
                return false;
            }

            NetworkPacket helloPacket = NetworkPacket.fromJson(jsonHello);

            if (helloPacket.getType() == PacketType.KYBER_SERVER_HELLO) {
                String payload = helloPacket.getPayload().getAsString();
                String[] parts = payload.split(":");

                byte[] serverKyberBytes = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] serverECBytes    = Base64.decode(parts[1], Base64.NO_WRAP);

                PublicKey serverKyberPub = CryptoHelper.decodeKyberPublicKey(serverKyberBytes);
                CryptoHelper.KEMResult kyberRes = CryptoHelper.encapsulate(serverKyberPub);

                KeyPair myECPair = CryptoHelper.generateECKeys();
                PublicKey serverECPub = CryptoHelper.decodeECPublicKey(serverECBytes);
                byte[] ecSecret = CryptoHelper.doECDH(myECPair.getPrivate(), serverECPub);

                sessionKey = CryptoHelper.combineSecrets(ecSecret, kyberRes.aesKey.getEncoded());

                String kyberCipherB64 = Base64.encodeToString(kyberRes.wrappedKey, Base64.NO_WRAP);
                String myECPubB64 = Base64.encodeToString(myECPair.getPublic().getEncoded(), Base64.NO_WRAP);

                String responsePayload = kyberCipherB64 + ":" + myECPubB64;

                NetworkPacket finishPacket = new NetworkPacket(PacketType.KYBER_CLIENT_FINISH, 0, responsePayload);

                synchronized (writeLock) {
                    out.println(finishPacket.toJson());
                    out.flush();
                }

                Log.d(TAG, "Handshake complete. AES tunnel established.");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Handshake failed unexpectedly", e);
            return false;
        }
    }

    public static void sendPacket(NetworkPacket packet) {
        new Thread(() -> {
            try {
                synchronized (writeLock) {
                    if (socket != null && !socket.isClosed()) {

                        if (sessionKey != null) {
                            String clearJson = packet.toJson();
                            byte[] encryptedBytes = CryptoHelper.encryptAndPack(sessionKey, clearJson);
                            String encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

                            NetworkPacket envelope = new NetworkPacket(PacketType.SECURE_ENVELOPE, currentUserId, encryptedBase64);

                            out.println(envelope.toJson());
                            out.flush();

                        } else {
                            out.println(packet.toJson());
                            out.flush();

                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send packet of type: " + packet.getType(), e);
            }
        }).start();
    }

    public static NetworkPacket readNextPacket() throws Exception {
        String jsonRaw = in.readLine();
        if(jsonRaw==null){
            return null;
        }

        NetworkPacket packet = NetworkPacket.fromJson(jsonRaw);


        if (sessionKey != null && packet.getType() == PacketType.SECURE_ENVELOPE) {
            try {
                String encryptedPayload = packet.getPayload().getAsString();
                byte[] packedBytes = Base64.decode(encryptedPayload, Base64.NO_WRAP);

                String originalJson = CryptoHelper.unpackAndDecrypt(sessionKey, packedBytes);
                packet = NetworkPacket.fromJson(originalJson);
            } catch (Exception e) {
                Log.e(TAG, "Tunnel decryption failed for secure envelope.", e);
                throw e;
            }
        }

        return packet;
    }

    public static void close() {
        try {
            isReading = false;
            sessionKey = null;
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();
            Log.i(TAG, "TCP Connection closed and resources released.");
        } catch (IOException e) {
            Log.e(TAG, "Error during connection shutdown", e);
        }
    }
    public static void setCurrentUserId(int id) {
        currentUserId = id;
    }
    public static int getCurrentUserId() {
        return currentUserId;
    }
    public static java.net.Socket getSocket() {
        return socket;
    }
}

