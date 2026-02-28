package com.example.tcpclient.utils;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class UdpCryptoUtils {
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_SIZE = 12;
    private static final int TAG_BIT_LENGTH = 128;
    private static final SecureRandom random = new SecureRandom();

    public static byte[] encrypt(SecretKey key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            byte[] iv = new byte[IV_SIZE];
            random.nextBytes(iv);

            GCMParameterSpec spec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] cipherText = cipher.doFinal(data);

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);
            return byteBuffer.array();
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] decrypt(SecretKey key, byte[] encryptedData) {
        try {
            if (encryptedData == null || encryptedData.length < IV_SIZE) return null;

            Cipher cipher = Cipher.getInstance(ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BIT_LENGTH, encryptedData, 0, IV_SIZE);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            return cipher.doFinal(encryptedData, IV_SIZE, encryptedData.length - IV_SIZE);
        } catch (Exception e) {
            return null;
        }
    }
}

