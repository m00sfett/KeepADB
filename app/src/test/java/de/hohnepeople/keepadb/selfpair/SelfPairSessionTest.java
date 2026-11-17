package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.nsd.NsdManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
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

    @Test
    public void cancelIsHarmlessBeforeAnyRunAndMayBeRepeated() {
        Context context = ApplicationProvider.getApplicationContext();
        NsdManager nsd = context.getSystemService(NsdManager.class);
        SelfPairSession session = new SelfPairSession(nsd, "de.hohnepeople.keepadb", visible -> {});
        assertNull("pairedFingerprint is initially null", session.pairedFingerprint());
        session.cancel();
        // safe to call again
        session.cancel();
    }
}
