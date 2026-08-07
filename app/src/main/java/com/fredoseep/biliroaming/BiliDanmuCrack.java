package com.fredoseep.biliroaming;

public class BiliDanmuCrack {
    private static final long CRCPOLYNOMIAL = 0xEDB88320L;
    private static final long[] crctable = new long[256];

    static {
        for (int i = 0; i < 256; i++) {
            long crcreg = i;
            for (int j = 0; j < 8; j++) {
                if ((crcreg & 1) != 0) {
                    crcreg = CRCPOLYNOMIAL ^ (crcreg >>> 1);
                } else {
                    crcreg >>>= 1;
                }
            }
            crctable[i] = crcreg;
        }
    }

    private static long crc32(byte[] input, int len) {
        long crcstart = 0xFFFFFFFFL;
        for (int i = 0; i < len; ++i) {
            int index = (int) ((crcstart ^ input[i]) & 0xFF);
            crcstart = (crcstart >>> 8) ^ crctable[index];
        }
        return crcstart;
    }

    private static int crc32lastindex(byte[] input, int len) {
        long crcstart = 0xFFFFFFFFL;
        int index = 0;
        for (int i = 0; i < len; ++i) {
            index = (int) ((crcstart ^ input[i]) & 0xFF);
            crcstart = (crcstart >>> 8) ^ crctable[index];
        }
        return index;
    }

    private static int getcrcindex(long t) {
        for (int i = 0; i < 256; i++) {
            if ((crctable[i] >>> 24) == t) {
                return i;
            }
        }
        return -1;
    }

    private static String deepCheck(byte[] iBytes, int len, int[] index) {
        long hash = crc32(iBytes, len);
        long tc = (hash & 0xFF) ^ index[2];
        if (!(tc <= 57 && tc >= 48)) return null;

        StringBuilder str = new StringBuilder();
        str.append((char) tc);

        hash = crctable[index[2]] ^ (hash >>> 8);
        tc = (hash & 0xFF) ^ index[1];
        if (!(tc <= 57 && tc >= 48)) return null;
        str.append((char) tc);

        hash = crctable[index[1]] ^ (hash >>> 8);
        tc = (hash & 0xFF) ^ index[0];
        if (!(tc <= 57 && tc >= 48)) return null;
        str.append((char) tc);

        return str.toString();
    }

    public static String crack(String input) {
        long ht;
        try {
            // Java 强转并补全异或
            ht = Long.parseLong(input, 16) ^ 0xFFFFFFFFL;
        } catch (Exception e) {
            return "Unknown";
        }

        int[] index = new int[4];
        for (int i = 3; i >= 0; i--) {
            index[3 - i] = getcrcindex(ht >>> (i * 8));
            long snum = crctable[index[3 - i]];
            ht ^= (snum >>> ((3 - i) * 8));
        }

        byte[] bytes = new byte[10];

        for (int i = 0; i < 100000000; i++) {
            int len = 0;
            int temp = i;

            // 将数字快速转换为 byte 数组 (等效于 String.valueOf(i).getBytes())
            if (temp == 0) {
                bytes[0] = '0';
                len = 1;
            } else {
                int p = 0;
                while (temp > 0) {
                    bytes[p++] = (byte) ('0' + (temp % 10));
                    temp /= 10;
                }
                len = p;
                for (int l = 0, r = len - 1; l < r; l++, r--) {
                    byte b = bytes[l];
                    bytes[l] = bytes[r];
                    bytes[r] = b;
                }
            }

            int lastindex = crc32lastindex(bytes, len);
            if (lastindex == index[3]) {
                String deepCheckData = deepCheck(bytes, len, index);
                if (deepCheckData != null) {
                    return i + deepCheckData; // 完美拼接并返回
                }
            }
        }
        return "Unknown";
    }
}