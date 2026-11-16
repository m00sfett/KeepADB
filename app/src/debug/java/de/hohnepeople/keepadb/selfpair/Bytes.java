package de.hohnepeople.keepadb.selfpair;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
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
}
