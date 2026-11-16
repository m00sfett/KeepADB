package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** Key schedule and record protection against the trace in RFC 8448 section 3. */
public class Tls13KeyScheduleTest {
    private static final byte[] SHARED = hex(
            "8bd4054fb55b9d63fdfbacf9f04b9f0d35e6d63f537563efd46272900f89492d");
    private static final byte[] HELLO_HASH = hex(
            "860c06edc07858ee8e78f0e7428c58edd6b43f2ca3e6e95f02ed063cf0e1cad8");
    private static final byte[] SERVER_FINISHED_HASH = hex(
            "9608102a0f1ccc6db6250b7b7e417b1a000eaada3daae4777a7686c9ff83df13");

    @Test
    public void derivesTheHandshakeSecrets() throws Exception {
        assertArrayEquals(
                hex("33ad0a1c607ec03b09e6cd9893680ce210adf300aa1f2660e1b22e10f170f92a"),
                Tls13KeySchedule.earlySecret());
        byte[] handshake = Tls13KeySchedule.handshakeSecret(SHARED);
        assertArrayEquals(
                hex("1dc826e93606aa6fdc0aadc12f741b01046aa6b99f691ed221a9f0ca043fbeac"),
                handshake);
        byte[] client = Tls13KeySchedule.deriveSecret(handshake, "c hs traffic", HELLO_HASH);
        assertArrayEquals(
                hex("b3eddb126e067f35a780b3abf45e2d8f3b1a950738f52e9600746a0e27a55a21"), client);
        byte[] server = Tls13KeySchedule.deriveSecret(handshake, "s hs traffic", HELLO_HASH);
        assertArrayEquals(
                hex("b67b7d690cc16c4e75e54213cb2d37b4e9c912bcded9105d42befd59d391ad38"), server);
        assertArrayEquals(hex("3fce516009c21727d0f2e4e86ee403bc"),
                Tls13KeySchedule.trafficKey(server));
        assertArrayEquals(hex("5d313eb2671276ee13000b30"), Tls13KeySchedule.trafficIv(server));
        assertArrayEquals(hex("dbfaa693d1762c5b666af5d950258d01"),
                Tls13KeySchedule.trafficKey(client));
        assertArrayEquals(hex("5bd3c71b836e0b76bb73265f"), Tls13KeySchedule.trafficIv(client));
    }

    @Test
    public void derivesTheApplicationSecrets() throws Exception {
        byte[] master = Tls13KeySchedule.masterSecret(Tls13KeySchedule.handshakeSecret(SHARED));
        assertArrayEquals(
                hex("18df06843d13a08bf2a449844c5f8a478001bc4d4c627984d5a41da8d0402919"), master);
        assertArrayEquals(
                hex("9e40646ce79a7f9dc05af8889bce6552875afa0b06df0087f792ebb7c17504a5"),
                Tls13KeySchedule.deriveSecret(master, "c ap traffic", SERVER_FINISHED_HASH));
        assertArrayEquals(
                hex("a11af9f05531f856ad47116b45a950328204b4f44bfb6b3a4b4f1f3fcb631643"),
                Tls13KeySchedule.deriveSecret(master, "s ap traffic", SERVER_FINISHED_HASH));
        assertArrayEquals(
                hex("fe22f881176eda18eb8f44529e6792c50c9a3f89452f68d8ae311b4309d3cf50"),
                Tls13KeySchedule.deriveSecret(master, "exp master", SERVER_FINISHED_HASH));
    }

    /** The RFC gives the Finished keys ("expanded") for both directions. */
    @Test
    public void derivesTheFinishedKeys() throws Exception {
        assertArrayEquals(
                hex("008d3b66f816ea559f96b537e885c31fc068bf492c652f01f288a1d8cdc19fc8"),
                Tls13KeySchedule.expandLabel(
                        hex("b67b7d690cc16c4e75e54213cb2d37b4e9c912bcded9105d42befd59d391ad38"),
                        "finished", new byte[0], 32));
        assertArrayEquals(
                hex("b80ad01015fb2f0bd65ff7d4da5d6bf83f84821d1f87fdc7d3c75b5a7b42d9c4"),
                Tls13KeySchedule.expandLabel(
                        hex("b3eddb126e067f35a780b3abf45e2d8f3b1a950738f52e9600746a0e27a55a21"),
                        "finished", new byte[0], 32));
    }

    /** "{client} send application_data record" and the server's reply from the RFC. */
    @Test
    public void protectsRecordsLikeTheRfcTrace() throws Exception {
        byte[] payload = new byte[50];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        byte[] clientSecret =
                hex("9e40646ce79a7f9dc05af8889bce6552875afa0b06df0087f792ebb7c17504a5");
        byte[] expected = hex("1703030043a23f7054b62c94d0affafe8228ba55cbefacea42f914aa66bcab3f2b"
                + "9819a8a5b46b395bd54a9a20441e2b62974e1f5a6292a2977014bd1e3deae63aeebb21694915e4");
        assertArrayEquals(expected, new Tls13RecordCipher(clientSecret).seal(23, payload, 0, 50));

        byte[] serverSecret =
                hex("a11af9f05531f856ad47116b45a950328204b4f44bfb6b3a4b4f1f3fcb631643");
        byte[] record = hex("17030300432e937e11ef4ac740e538ad36005fc4a46932fc3225d05f82aa1b36e30e"
                + "faf97d90e6dffc602dcb501a59a8fcc49c4bf2e5f0a21c0047c2abf332540dd032e167c2955d");
        byte[] header = java.util.Arrays.copyOf(record, 5);
        byte[] sealed = java.util.Arrays.copyOfRange(record, 5, record.length);
        // In the trace the server sent a NewSessionTicket first, so this is its second record.
        Tls13RecordCipher serverCipher = new Tls13RecordCipher(serverSecret);
        serverCipher.seal(22, new byte[0], 0, 0);
        byte[] opened = serverCipher.open(header, sealed);
        assertArrayEquals(Bytes.concat(payload, new byte[] {23}), opened);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsATamperedRecord() throws Exception {
        byte[] secret = hex("a11af9f05531f856ad47116b45a950328204b4f44bfb6b3a4b4f1f3fcb631643");
        byte[] record = new Tls13RecordCipher(secret).seal(23, new byte[] {1, 2, 3}, 0, 3);
        record[7] ^= 1;
        new Tls13RecordCipher(secret).open(java.util.Arrays.copyOf(record, 5),
                java.util.Arrays.copyOfRange(record, 5, record.length));
    }

    private static byte[] hex(String hex) {
        return Bytes.hex(hex);
    }
}
