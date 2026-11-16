package de.hohnepeople.keepadb.selfpair;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Small byte helpers shared by the self-pairing classes (#962). */
final class Bytes {
    private Bytes() {
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(out, offset, length - offset);
            if (read < 0) {
                throw new EOFException("stream closed after " + offset + " of " + length);
            }
            offset += read;
        }
        return out;
    }

    static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** Fixed-length little-endian encoding of a non-negative public number. */
    static byte[] toLittleEndian(BigInteger value, int length) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[length];
        for (int i = 0; i < big.length; i++) {
            int index = big.length - 1 - i;
            if (i < length) {
                out[i] = big[index];
            } else if (big[index] != 0) {
                throw new IllegalArgumentException("value does not fit");
            }
        }
        return out;
    }
}
