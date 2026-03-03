package com.example.tcpclient.utils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NalUtils {
    public static ByteBuffer extractNal(byte[] data, int nalIndex) {
        int[] boundaries = findNalBoundaries(data);
        if (nalIndex >= boundaries.length - 1) return ByteBuffer.wrap(data);
        int start = boundaries[nalIndex];
        int end = boundaries[nalIndex + 1];
        return ByteBuffer.wrap(Arrays.copyOfRange(data, start, end));
    }

    public static int[] findNalBoundaries(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 4; i < data.length - 4; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                starts.add(i);
            }
        }
        starts.add(data.length);
        int[] result = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) result[i] = starts.get(i);
        return result;
    }

    public static int getNalType(byte[] data) {
        if (data.length > 4 && data[0]==0 && data[1]==0 && data[2]==0 && data[3]==1)
            return data[4] & 0x1F;
        if (data.length > 3 && data[0]==0 && data[1]==0 && data[2]==1)
            return data[3] & 0x1F;
        return -1;
    }
}
