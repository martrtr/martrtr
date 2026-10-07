package io.openai.footdraw;

final class JieliStreamParser {
    interface Sink { void onFrame(byte[] jpeg, long sequence, int type, int channel); }

    private static final int HEADER = 13;
    private static final int MAX_FRAME = 1024 * 1024;
    private final Sink sink;
    private final byte[] frame = new byte[MAX_FRAME];
    private long sequence = -1;
    private int type = -1, channel = -1, high = 0;

    JieliStreamParser(Sink sink) { this.sink = sink; }

    void accept(byte[] packet, int start, int length) {
        int pos = start, end = start + length;
        while (pos + 2 + HEADER <= end) {
            int dataLen = u16(packet, pos);
            pos += 2;
            if (dataLen <= 0 || pos + HEADER + dataLen > end) return;

            int newChannel = packet[pos] & 0xff;
            long newSequence = u32(packet, pos + 4);
            long offsetLong = u32(packet, pos + 8);
            int flags = packet[pos + 12] & 0xff;
            int newType = flags & 0x7f;
            boolean last = (flags & 0x80) != 0;
            pos += HEADER;

            if (newType >= 2 && newType <= 7 &&
                    offsetLong <= MAX_FRAME && offsetLong + dataLen <= MAX_FRAME) {
                int offset = (int) offsetLong;
                if (newSequence != sequence || newType != type || newChannel != channel) {
                    sequence = newSequence; type = newType; channel = newChannel; high = 0;
                }
                System.arraycopy(packet, pos, frame, offset, dataLen);
                high = Math.max(high, offset + dataLen);
                if (last) {
                    int size = offset + dataLen;
                    if (size > 0 && size <= high && sink != null) {
                        byte[] complete = new byte[size];
                        System.arraycopy(frame, 0, complete, 0, size);
                        sink.onFrame(complete, sequence, type, channel);
                    }
                    high = 0;
                }
            }
            pos += dataLen;
        }
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int p) {
        return ((long)b[p] & 0xffL) | (((long)b[p+1] & 0xffL) << 8) |
                (((long)b[p+2] & 0xffL) << 16) | (((long)b[p+3] & 0xffL) << 24);
    }
}
