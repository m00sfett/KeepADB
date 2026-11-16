package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SelfPairSessionTest {
    @Test
    public void aCodeIsExactlySixAsciiDigits() {
        assertTrue(SelfPairSession.isCode("012345"));
        assertFalse(SelfPairSession.isCode(null));
        assertFalse(SelfPairSession.isCode(""));
        assertFalse(SelfPairSession.isCode("12345"));
        assertFalse(SelfPairSession.isCode("1234567"));
        assertFalse(SelfPairSession.isCode("12 345"));
        assertFalse(SelfPairSession.isCode("12345a"));
        // Digits of other scripts are not what Android's dialog shows.
        assertFalse(SelfPairSession.isCode("١٢٣٤٥٦"));
    }
}
