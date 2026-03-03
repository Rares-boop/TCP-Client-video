package com.example.tcpclient.network;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.example.tcpclient.utils.NalUtils;
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
    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int MTU_LIMIT = 1400;
    private static final int SERVER_VIDEO_PORT = 15557;
    private MediaCodec encoder;
    private MediaCodec decoder;
    private Surface encoderInputSurface;
    private DatagramSocket videoSocket;
    private SecretKey sessionKey;
    private final int myId;
    private int targetId;
    private final String serverIp;
    private volatile boolean isVideoActive = false;
    private final Surface remoteSurface;
    private final Map<Integer, byte[][]> frameCollector = new HashMap<>();
    private final Map<Integer, Integer> sliceCounter = new HashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> decodedFramesQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean isDecoderConfigured = false;

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

    public Surface getEncoderSurface() {
        return encoderInputSurface;
    }

    private void setupEncoder() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2500000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            encoderInputSurface = encoder.createInputSurface();

            encoder.start();
            startEncoderLoop();
            Log.d(TAG, "Encoder video started with Surface mode!");
        } catch (Exception e) {
            Log.e(TAG, "Encoder setup failed", e);
        }
    }

    private void startEncoderLoop() {
        new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pendingConfig = null;
            try {
                InetAddress addr = InetAddress.getByName(serverIp);
                while (isVideoActive) {
                    if (encoder == null) {
                        break;
                    }
                    try {
                        int index = encoder.dequeueOutputBuffer(info, 50000);

                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            Thread.sleep(10);
                            continue;
                        }

                        if (index >= 0) {
                            ByteBuffer outBuf = encoder.getOutputBuffer(index);

                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                if (outBuf == null) {
                                    encoder.releaseOutputBuffer(index, false);
                                    continue;
                                }
                                pendingConfig = new byte[info.size];
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                outBuf.get(pendingConfig);
                                encoder.releaseOutputBuffer(index, false);
                                Log.d(TAG, "SPS/PPS saved: " + pendingConfig.length + " bytes");
                                continue;
                            }

                            if (outBuf != null && info.size > 0) {
                                byte[] frameData = new byte[info.size];
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                outBuf.get(frameData);

                                byte[] toSend;
                                int nalT = frameData.length > 4 ? (frameData[4] & 0x1F) : -1;
                                boolean isIDR = nalT == 5;

                                if (isIDR && pendingConfig != null) {
                                    toSend = new byte[pendingConfig.length + frameData.length];
                                    System.arraycopy(pendingConfig, 0, toSend, 0, pendingConfig.length);
                                    System.arraycopy(frameData, 0, toSend, pendingConfig.length, frameData.length);
                                    Log.d(TAG, "Sent IDR: " + toSend.length + " bytes");
                                } else {
                                    toSend = frameData;
                                }

                                byte[] encrypted = UdpCryptoUtils.encrypt(sessionKey, toSend);
                                if (encrypted != null) {
                                    sliceAndSend(encrypted, addr);
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
            } catch (Exception e) {
                Log.e(TAG, "Fatal encoder loop error", e);
            }
        }).start();
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
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    try {
                        if (!isDecoderConfigured) {
                            codec.queueInputBuffer(index, 0, 0, System.currentTimeMillis() * 1000, 0);
                            return;
                        }
                        byte[] frameData = decodedFramesQueue.poll();
                        ByteBuffer inputBuffer = codec.getInputBuffer(index);

                        if (frameData != null && inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(frameData);
                            codec.queueInputBuffer(index, 0, frameData.length, System.currentTimeMillis() * 1000, 0);
                        } else {
                            codec.queueInputBuffer(index, 0, 0, System.currentTimeMillis() * 1000, 0);
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    try {
                        codec.releaseOutputBuffer(index, true);
                    } catch (IllegalStateException ignored) {}
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    Log.e(TAG, "DECODER ERROR: " + e.getMessage() + " diagnostic=" + e.getDiagnosticInfo());
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    Log.d(TAG, "Decoder output format: " + format);
                }
            });
            Log.d(TAG, "Decoder ready!");
        } catch (Exception e) {
            Log.e(TAG, "Decoder setup failed", e);
        }
    }

    public void receiveVideoSlice(byte[] fullPacketData) {
        if (!isVideoActive || fullPacketData.length < 17) {
            return;
        }

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
                Integer count = sliceCounter.get(frameId);
                if (count == null) {
                    return;
                }
                int currentCount = count + 1;
                sliceCounter.put(frameId, currentCount);

                if (currentCount == totalSlices) {
                    assembleAndRender(frameId);
                }
            }
        }
    }

    private void assembleAndRender(int frameId) {
        byte[][] slices = frameCollector.remove(frameId);
        sliceCounter.remove(frameId);

        if (decoder == null) {
            return;
        }

        if (slices == null) {
            return;
        }

        int totalSize = 0;
        for (byte[] s : slices) {
            totalSize += s.length;
        }

        ByteBuffer fullFrame = ByteBuffer.allocate(totalSize);
        for (byte[] s : slices) {
            fullFrame.put(s);
        }

        byte[] clearData = UdpCryptoUtils.decrypt(sessionKey, fullFrame.array());
        if (clearData == null) {
            return;
        }

        int nalType = NalUtils.getNalType(clearData);

        if (nalType == 7) {
            if (!isDecoderConfigured) {
                try {
                    ByteBuffer spsOnly = NalUtils.extractNal(clearData, 0);
                    ByteBuffer ppsOnly = NalUtils.extractNal(clearData, 1);

                    Log.d(TAG, "Config decoder: SPS=" + spsOnly.remaining() + " PPS=" + ppsOnly.remaining());

                    MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    }
                    format.setByteBuffer("csd-0", spsOnly);
                    format.setByteBuffer("csd-1", ppsOnly);

                    decoder.configure(format, remoteSurface, null, 0);
                    decoder.start();
                    isDecoderConfigured = true;
                    Log.d(TAG, "Decoder configured!");
                } catch (Exception e) {
                    Log.e(TAG, "Decoder config failed", e);
                    return;
                }
            }
            decodedFramesQueue.add(clearData);
            return;
        }

        if (isDecoderConfigured) {
            decodedFramesQueue.add(clearData);
        }
    }

    public void endVideo() {
        isVideoActive = false;
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        videoSocket = null;
        isDecoderConfigured = false;

        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }
        if (encoder != null) {
            try { encoder.stop(); encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (decoder != null) {
            try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
        synchronized (frameCollector) {
            frameCollector.clear();
            sliceCounter.clear();
        }
        decodedFramesQueue.clear();
    }
}

