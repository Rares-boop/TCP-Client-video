package com.example.tcpclient.network;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import com.example.tcpclient.utils.UdpCryptoUtils;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;

public class VideoCallManager {
    private static final String TAG = "VideoCallManager";
    private static final String MIME_TYPE = "video/avc";
    private static final int VIDEO_WIDTH = 480;
    private static final int VIDEO_HEIGHT = 640;
    private static final int MTU_LIMIT = 1100;
    private static final int SERVER_VIDEO_PORT = 15557;
    private MediaCodec encoder;
    private MediaCodec decoder;
    private DatagramSocket videoSocket;
    private SecretKey sessionKey;
    private int myId;
    private int targetId;
    private String serverIp;
    private boolean isVideoActive = false;
    private Surface remoteSurface;
    private Surface inputSurface;
    private final Map<Integer, byte[][]> frameCollector = new HashMap<>();
    private final Map<Integer, Integer> sliceCounter = new HashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> decodedFramesQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private byte[] configFrame = null;
    private boolean isDecoderConfigured = false;
    private Thread configBlasterThread = null; // Tancul care bombardeaza

    public VideoCallManager(String serverIp, int myId, Surface remoteSurface) {
        this.serverIp = serverIp;
        this.myId = myId;
        this.remoteSurface = remoteSurface;
    }

    public void startVideo(int targetId, SecretKey key, DatagramSocket sharedVideoSocket) {
        this.targetId = targetId;
        this.sessionKey = key;
        this.videoSocket = sharedVideoSocket;
        this.isVideoActive = true;
        this.isDecoderConfigured = false;

        setupDecoder();
        setupEncoder();
    }

    private void setupEncoder() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // I-Frame la fiecare secunda

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            startEncoderLoop();
            Log.d(TAG, "Encoder video pornit blană!");
        } catch (Exception e) { Log.e(TAG, "Encoder setup failed", e); }
    }

    public void encodeFrame(byte[] yuvData) {
        if (!isVideoActive || encoder == null) return;
        try {
            int inputBufferIndex = encoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    int capacity = inputBuffer.capacity();
                    int lengthToCopy = Math.min(yuvData.length, capacity);
                    inputBuffer.put(yuvData, 0, lengthToCopy);
                    encoder.queueInputBuffer(inputBufferIndex, 0, lengthToCopy, System.nanoTime() / 1000, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Encode frame error", e);
        }
    }

    public Surface getEncoderInputSurface() {
        return inputSurface;
    }

    private void startEncoderLoop() {
        new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                InetAddress addr = InetAddress.getByName(serverIp);
                while (isVideoActive) {
                    if (encoder == null) break;

                    try {
                        int index = encoder.dequeueOutputBuffer(info, 50000);

                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            Thread.sleep(10);
                            continue;
                        }

                        if (index >= 0) {
                            ByteBuffer outBuf = encoder.getOutputBuffer(index);

                            // AM PRINS DICȚIONARUL!
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                configFrame = new byte[info.size];
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                outBuf.get(configFrame);

                                // PORNIM BLASTER-UL CARE TRIMITE DICȚIONARUL CONTINUU
                                startConfigBlaster(addr);

                                encoder.releaseOutputBuffer(index, false);
                                continue;
                            }

                            if (outBuf != null && info.size > 0) {
                                byte[] frameData = new byte[info.size];
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                outBuf.get(frameData);

                                byte[] encryptedFrame = UdpCryptoUtils.encrypt(sessionKey, frameData);
                                if (encryptedFrame != null) {
                                    sliceAndSend(encryptedFrame, addr);
                                }
                            }
                            encoder.releaseOutputBuffer(index, false);
                        }
                    } catch (IllegalStateException e) {
                        break;
                    } catch (Throwable t) {
                        Thread.sleep(100);
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Fatal loop error", e); }
        }).start();
    }

    // ASTA E TANCUL CARE BUBUIE REȚEAUA CU CONFIGURAREA PÂNĂ CÂND CELĂLALT RĂSPUNDE
    private void startConfigBlaster(InetAddress addr) {
        if (configBlasterThread != null) return;
        configBlasterThread = new Thread(() -> {
            while (isVideoActive && configFrame != null) {
                try {
                    byte[] encryptedConfig = UdpCryptoUtils.encrypt(sessionKey, configFrame);
                    if (encryptedConfig != null) {
                        sliceAndSend(encryptedConfig, addr);
                    }
                    Thread.sleep(500); // Trage la fiecare jumatate de secunda!
                } catch (Exception e) { break; }
            }
        });
        configBlasterThread.start();
    }

    private void sliceAndSend(byte[] fullData, InetAddress addr) throws Exception {
        int totalSlices = (int) Math.ceil((double) fullData.length / MTU_LIMIT);
        int currentFrameId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);

        for (int i = 0; i < totalSlices; i++) {
            int start = i * MTU_LIMIT;
            int length = Math.min(MTU_LIMIT, fullData.length - start);

            ByteBuffer packetBuf = ByteBuffer.allocate(17 + length);
            packetBuf.putInt(myId);
            packetBuf.putInt(targetId);
            packetBuf.put((byte) 1);
            packetBuf.putInt(currentFrameId);
            packetBuf.putShort((short) i);
            packetBuf.putShort((short) totalSlices);
            packetBuf.put(fullData, start, length);

            byte[] raw = packetBuf.array();
            if (videoSocket != null && !videoSocket.isClosed()) {
                videoSocket.send(new DatagramPacket(raw, raw.length, addr, SERVER_VIDEO_PORT));
            }
        }
    }

    private void setupDecoder() {
        try {
            decoder = MediaCodec.createDecoderByType(MIME_TYPE);
            decoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    try {
                        byte[] frameData = decodedFramesQueue.poll();
                        ByteBuffer inputBuffer = codec.getInputBuffer(index);

                        if (frameData != null && inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(frameData);
                            codec.queueInputBuffer(index, 0, frameData.length, System.currentTimeMillis() * 1000, 0);
                        } else {
                            codec.queueInputBuffer(index, 0, 0, System.currentTimeMillis() * 1000, 0);
                        }
                    } catch (Exception e) {}
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                    try {
                        codec.releaseOutputBuffer(index, true);
                    } catch (IllegalStateException e) {}
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException e) {}

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {}
            });
            Log.d(TAG, "Decoderul e pregătit, AȘTEPTĂM BOMBARDAMENTUL CU CONFIGURAREA!");
        } catch (Exception e) {}
    }

    public void receiveVideoSlice(byte[] fullPacketData) {
        if (!isVideoActive || fullPacketData.length < 17) return;

        ByteBuffer bb = ByteBuffer.wrap(fullPacketData);
        bb.position(9);

        int frameId = bb.getInt();
        short sliceIdx = bb.getShort();
        short totalSlices = bb.getShort();

        byte[] data = new byte[fullPacketData.length - 17];
        bb.get(data);

        synchronized (frameCollector) {
            if (!frameCollector.containsKey(frameId)) {
                if (frameCollector.size() > 30) {
                    frameCollector.clear();
                    sliceCounter.clear();
                }
                frameCollector.put(frameId, new byte[totalSlices][]);
                sliceCounter.put(frameId, 0);
            }

            byte[][] slices = frameCollector.get(frameId);
            if (slices != null && slices[sliceIdx] == null) {
                slices[sliceIdx] = data;
                int currentCount = sliceCounter.get(frameId) + 1;
                sliceCounter.put(frameId, currentCount);

                if (currentCount == totalSlices) {
                    assembleAndRender(frameId, totalSlices);
                }
            }
        }
    }

    private void assembleAndRender(int frameId, int totalSlices) {
        byte[][] slices = frameCollector.remove(frameId);
        sliceCounter.remove(frameId);

        if (decoder == null) return;

        int totalSize = 0;
        for (byte[] s : slices) totalSize += s.length;

        ByteBuffer fullFrame = ByteBuffer.allocate(totalSize);
        for (byte[] s : slices) fullFrame.put(s);

        byte[] clearData = UdpCryptoUtils.decrypt(sessionKey, fullFrame.array());

        if (clearData != null) {
            // DETECTARE INTELIGENTĂ A PACHETELOR NAL
            int nalType = -1;
            if (clearData.length > 4 && clearData[0] == 0 && clearData[1] == 0 && clearData[2] == 0 && clearData[3] == 1) {
                nalType = clearData[4] & 0x1F;
            } else if (clearData.length > 3 && clearData[0] == 0 && clearData[1] == 0 && clearData[2] == 1) {
                nalType = clearData[3] & 0x1F;
            }

            // DACĂ ESTE DICȚIONAR (SPS)
            if (nalType == 7 || nalType == 8) {
                if (!isDecoderConfigured) {
                    try {
                        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(clearData));

                        decoder.configure(format, remoteSurface, null, 0);
                        decoder.start();
                        isDecoderConfigured = true;
                        Log.d(TAG, "BINGO! Am prins Config Frame din bombardament! DECODER PORNIT!");
                    } catch (Exception e) {
                        Log.e(TAG, "Eroare la configurare", e);
                    }
                }
                // Indiferent daca e configurat sau nu, NU bagam pachetul SPS in coada de imagini!
                return;
            }

            // DACĂ DECODERUL E PORNIT, BAGĂ POZA PE ECRAN!
            if (isDecoderConfigured) {
                decodedFramesQueue.add(clearData);
            }
        }
    }

    public void endVideo() {
        isVideoActive = false;
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        videoSocket = null;
        isDecoderConfigured = false;
        configBlasterThread = null;

        if (encoder != null) {
            try { encoder.stop(); encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (decoder != null) {
            try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        synchronized (frameCollector) {
            frameCollector.clear();
            sliceCounter.clear();
        }
        decodedFramesQueue.clear();
        Log.d(TAG, "Video Call ended cleanly.");
    }
}