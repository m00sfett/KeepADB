package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class KeepADBTest {

    @Before
    public void setUp() {
        KeepADB.resetForTesting();
    }

    @Test
    public void testConsumeUserDisabled() {
        assertFalse(KeepADB.consumeUserDisabled());
    }

    @Test
    public void userDisabledFlagLifecycle() {
        assertFalse(KeepADB.isUserDisabled());
        assertFalse(KeepADB.consumeUserDisabled());
    }

    @Test
    public void constantsArePlausible() {
        assertTrue(KeepADB.TOGGLE_COOLDOWN_MS >= 1000);
        assertTrue(KeepADB.RECOVERY_PULSE_OFF_MS >= 500);
        assertEquals("adb_wifi_enabled", KeepADB.KEY);
    }

    @Test
    public void wasLastExplicitIntentOffLifecycleAndReset() {
        assertFalse(KeepADB.wasLastExplicitIntentOff());
        assertFalse(KeepADB.wasLastExplicitIntentOff(null));

        KeepADB.resetForTesting();
        assertFalse(KeepADB.wasLastExplicitIntentOff());
    }
}
