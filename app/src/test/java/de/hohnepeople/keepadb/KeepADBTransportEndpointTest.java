package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Plain-object coverage for #538's transport value type; the aggregation scenarios live in
 * {@code KeepADBTransportOverviewTest}. */
public class KeepADBTransportEndpointTest {

    @Test
    public void withPrimaryReturnsACopyWithOnlyThePrimaryFlagChanged() {
        KeepADBTransportEndpoint original = new KeepADBTransportEndpoint(
                KeepADBTransportEndpoint.Type.VPN, "100.64.1.2", 5555, 123L, false);

        KeepADBTransportEndpoint primary = original.withPrimary(true);

        assertTrue(primary.primary);
        assertFalse("The original instance must stay unchanged (immutability)", original.primary);
        assertEquals(original.type, primary.type);
        assertEquals(original.host, primary.host);
        assertEquals(original.port, primary.port);
        assertEquals(original.verifiedAtMs, primary.verifiedAtMs);
    }

    @Test
    public void withPrimaryIsANoOpWhenTheFlagAlreadyMatches() {
        KeepADBTransportEndpoint original = new KeepADBTransportEndpoint(
                KeepADBTransportEndpoint.Type.VPN, "100.64.1.2", 5555, 0, true);

        assertSame("No new instance is needed when nothing would change",
                original, original.withPrimary(true));
    }
}
