package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks {@link Spake2} bit for bit against vectors produced by BoringSSL itself (#962 gate 1).
 * The vectors cover all eight values of the low three bits of the reduced password hash, so
 * every branch of the "password scalar hack" is exercised.
 */
public class Spake2VectorTest {
    private static final byte[] CLIENT = Bytes.concat(Bytes.ascii("adb pair client"), new byte[1]);
    private static final byte[] SERVER = Bytes.concat(Bytes.ascii("adb pair server"), new byte[1]);

    @Test
    public void matchesBoringSslForEveryVector() throws Exception {
        List<Map<String, byte[]>> vectors = load();
        assertEquals(8, vectors.size());
        for (Map<String, byte[]> vector : vectors) {
            Spake2 spake = new Spake2(CLIENT, SERVER);
            byte[] message = spake.generateMessage(vector.get("password"),
                    randomFor(vector.get("alice_private")));
            assertArrayEquals(vector.get("alice_msg"), message);
            byte[] key = spake.processMessage(vector.get("bob_msg"));
            assertArrayEquals(vector.get("key"), key);
            assertArrayEquals(vector.get("aes_key"), PairingCipher.hkdfSha256(key,
                    Bytes.ascii("adb pairing_auth aes-128-gcm key"), 16));
        }
    }

    @Test
    public void wrongPasswordYieldsDifferentKey() throws Exception {
        Map<String, byte[]> vector = load().get(0);
        byte[] password = vector.get("password").clone();
        password[0] ^= 1;
        Spake2 spake = new Spake2(CLIENT, SERVER);
        spake.generateMessage(password, randomFor(vector.get("alice_private")));
        assertFalse(Arrays.equals(vector.get("key"), spake.processMessage(vector.get("bob_msg"))));
    }

    @Test
    public void swappedNamesYieldDifferentKey() throws Exception {
        Map<String, byte[]> vector = load().get(0);
        Spake2 spake = new Spake2(SERVER, CLIENT);
        spake.generateMessage(vector.get("password"), randomFor(vector.get("alice_private")));
        assertFalse(Arrays.equals(vector.get("key"), spake.processMessage(vector.get("bob_msg"))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPeerMessageOffTheCurve() throws Exception {
        Map<String, byte[]> vector = load().get(0);
        Spake2 spake = new Spake2(CLIENT, SERVER);
        spake.generateMessage(vector.get("password"));
        byte[] bad = new byte[32];
        bad[0] = 2;
        spake.processMessage(bad);
    }

    /** BoringSSL's private key is reduce(random) * 8, so private / 8 reproduces it as input. */
    private static byte[] randomFor(byte[] privateKey) {
        BigInteger value = Ed25519Reference.fromLittleEndian(privateKey);
        assertEquals(0, value.intValue() & 7);
        return Ed25519Reference.toLittleEndian(value.shiftRight(3), 64);
    }

    private static List<Map<String, byte[]>> load() throws Exception {
        List<Map<String, byte[]>> vectors = new ArrayList<>();
        Map<String, byte[]> current = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Spake2VectorTest.class.getResourceAsStream("/selfpair/spake2-vectors.txt"),
                StandardCharsets.US_ASCII))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith("# vector")) {
                    current = new HashMap<>();
                    vectors.add(current);
                } else if (current != null && line.contains("=")) {
                    int split = line.indexOf('=');
                    current.put(line.substring(0, split), Bytes.hex(line.substring(split + 1)));
                }
            }
        }
        return vectors;
    }
}
