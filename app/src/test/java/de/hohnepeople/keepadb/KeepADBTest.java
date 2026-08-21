package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class KeepADBTest {

    @Test
    public void testConsumeUserDisabled() {
        assertFalse(KeepADB.consumeUserDisabled());
    }
}
