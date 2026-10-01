package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #701: the persisted retry record of one pending register cleanup, tested on its own against real
 * (shadowed) preferences. Every number is spelled out as a literal on purpose: the lifecycle tests
 * drive the client with the repository's own constants, so a changed budget, expiry or backoff
 * would move them along unnoticed. These pin the stored format, the key, the table and the
 * boundaries themselves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PendingCleanupRetryRepositoryTest {
    private static final String ENTRY = "http://old.example/register";
    private static final String RECORD_KEY_PREFIX =
            "register_webhook_pending_cleanup_retry_state:";
    private static final long DAY_MS = 86_400_000L;

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    // ---- The budget numbers. ----

    @Test
    public void theBudgetNumbersAreThePinnedOnes() {
        assertEquals(3, PendingCleanupRetryRepository.MAX_ATTEMPTS);
        assertEquals(DAY_MS, PendingCleanupRetryRepository.EXPIRY_MS);
        assertEquals(30_000L, PendingCleanupRetryRepository.INITIAL_BACKOFF_MS);
        assertEquals(300_000L, PendingCleanupRetryRepository.MAX_BACKOFF_MS);
        // The FIFO of stored entries is a separate bound, owned by KeepADBPreferences.
        assertEquals(4, KeepADBPreferences.MAX_PENDING_CLEANUPS);
    }

    @Test
    public void backoffDoublesFromThirtySecondsAndStopsAtFiveMinutes() {
        assertEquals(30_000L, PendingCleanupRetryRepository.backoffMs(1));
        assertEquals(60_000L, PendingCleanupRetryRepository.backoffMs(2));
        assertEquals(120_000L, PendingCleanupRetryRepository.backoffMs(3));
        assertEquals(240_000L, PendingCleanupRetryRepository.backoffMs(4));
        assertEquals(300_000L, PendingCleanupRetryRepository.backoffMs(5));
        assertEquals(300_000L, PendingCleanupRetryRepository.backoffMs(6));
        assertEquals(300_000L, PendingCleanupRetryRepository.backoffMs(1_000));
    }

    // ---- Key and stored format. ----

    @Test
    public void failuresAreStoredUnderTheDocumentedKeyAndFormat() {
        assertFalse(PendingCleanupRetryRepository.recordFailure(context, ENTRY, 1_000L));
        assertEquals("attempts,nextAttemptAt,expiresAt of the first failure",
                "1,31000,86401000", prefs.getString(RECORD_KEY_PREFIX + ENTRY, null));

        // The second failure doubles the backoff and keeps the expiry of the first one.
        assertFalse(PendingCleanupRetryRepository.recordFailure(context, ENTRY, 31_000L));
        assertEquals("2,91000,86401000", prefs.getString(RECORD_KEY_PREFIX + ENTRY, null));

        PendingCleanupRetryRepository.RetryState state =
                PendingCleanupRetryRepository.read(context, ENTRY, 91_000L);
        assertEquals(2, state.attempts);
        assertEquals(91_000L, state.nextAttemptAt);
        assertEquals(86_401_000L, state.expiresAt);
    }

    @Test
    public void theFailureThatUsesUpTheBudgetStoresNothingAndReportsExhaustion() {
        PendingCleanupRetryRepository.recordFailure(context, ENTRY, 1_000L);
        PendingCleanupRetryRepository.recordFailure(context, ENTRY, 31_000L);

        assertTrue("the third failure uses up the budget of three attempts",
                PendingCleanupRetryRepository.recordFailure(context, ENTRY, 91_000L));
        assertEquals("the caller drops the entry, so no third record may be written",
                "2,91000,86401000", prefs.getString(RECORD_KEY_PREFIX + ENTRY, null));
    }

    @Test
    public void aRecordBelongsToTheRawEntryNotToItsCanonicalResource() {
        String withCredentials = "http://user:secret@new.example/register";
        String sanitized = "http://new.example/register";

        PendingCleanupRetryRepository.recordFailure(context, withCredentials, 1_000L);

        assertEquals("1,31000,86401000",
                prefs.getString(RECORD_KEY_PREFIX + withCredentials, null));
        assertNull("canonicalizing entries into resources is the client's job",
                prefs.getString(RECORD_KEY_PREFIX + sanitized, null));
        assertEquals(0, PendingCleanupRetryRepository.read(context, sanitized, 5L).attempts);
    }

    @Test
    public void removeDeletesOnlyTheRecordOfThatEntryAndNoStoredEntry() {
        String other = "http://other.example/register";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, ENTRY);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, other);
        PendingCleanupRetryRepository.recordFailure(context, ENTRY, 1_000L);
        PendingCleanupRetryRepository.recordFailure(context, other, 1_000L);

        PendingCleanupRetryRepository.remove(context, ENTRY);

        assertNull(prefs.getString(RECORD_KEY_PREFIX + ENTRY, null));
        assertEquals("1,31000,86401000", prefs.getString(RECORD_KEY_PREFIX + other, null));
        assertTrue("the stored entries are owned by KeepADBPreferences, not by this record",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(ENTRY));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(other));
    }

    @Test
    public void recordingAFailureLeavesTheStoredEntriesUntouched() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, ENTRY);
        String entriesBefore = prefs.getString("register_webhook_pending_cleanup_order", null);

        PendingCleanupRetryRepository.recordFailure(context, ENTRY, 1_000L);
        PendingCleanupRetryRepository.recordFailure(context, ENTRY, 31_000L);
        PendingCleanupRetryRepository.recordFailure(context, ENTRY, 91_000L);

        assertEquals(entriesBefore, prefs.getString("register_webhook_pending_cleanup_order", null));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(ENTRY));
    }

    // ---- Reading. ----

    @Test
    public void aMissingOrMalformedRecordReadsAsAFreshRecord() {
        assertFresh(PendingCleanupRetryRepository.read(context, ENTRY, 5_000L), 5_000L);

        for (String malformed : new String[] {"", "   ", "1,2", "1,2,3,4", "x,2,3", "1,y,3",
                "1,2,z", "1.5,2,3"}) {
            prefs.edit().putString(RECORD_KEY_PREFIX + ENTRY, malformed).commit();
            assertFresh(PendingCleanupRetryRepository.read(context, ENTRY, 5_000L), 5_000L);
        }
    }

    @Test
    public void aWellFormedRecordIsReadBackExactlyAndANegativeCountIsClampedToZero() {
        prefs.edit().putString(RECORD_KEY_PREFIX + ENTRY, "2,91000,86401000").commit();
        PendingCleanupRetryRepository.RetryState state =
                PendingCleanupRetryRepository.read(context, ENTRY, 777L);
        assertEquals(2, state.attempts);
        assertEquals(91_000L, state.nextAttemptAt);
        assertEquals(86_401_000L, state.expiresAt);

        prefs.edit().putString(RECORD_KEY_PREFIX + ENTRY, "-5,10,20").commit();
        state = PendingCleanupRetryRepository.read(context, ENTRY, 777L);
        assertEquals(0, state.attempts);
        assertEquals(10L, state.nextAttemptAt);
        assertEquals(20L, state.expiresAt);
    }

    @Test
    public void expiryExhaustionAndDueTimeAreDecidedAtTheirBoundaries() {
        PendingCleanupRetryRepository.RetryState state =
                new PendingCleanupRetryRepository.RetryState(2, 1_000L, 5_000L);

        assertFalse(state.isExpired(4_999L));
        assertTrue("expired exactly at expiresAt", state.isExpired(5_000L));
        assertFalse(state.isDue(999L));
        assertTrue("due exactly at nextAttemptAt", state.isDue(1_000L));
        assertFalse("two failed attempts leave the third one", state.isExhausted());
        assertTrue(new PendingCleanupRetryRepository.RetryState(3, 0L, 1L).isExhausted());
    }

    @Test
    public void timesSaturateInsteadOfOverflowing() {
        long nearMax = Long.MAX_VALUE - 10L;

        assertEquals(Long.MAX_VALUE,
                PendingCleanupRetryRepository.read(context, ENTRY, nearMax).expiresAt);

        PendingCleanupRetryRepository.recordFailure(context, ENTRY, nearMax);
        assertEquals("1," + Long.MAX_VALUE + "," + Long.MAX_VALUE,
                prefs.getString(RECORD_KEY_PREFIX + ENTRY, null));
    }

    @Test
    public void aMissingContextOrEntryIsHarmless() {
        assertFresh(PendingCleanupRetryRepository.read(null, ENTRY, 5_000L), 5_000L);
        assertFresh(PendingCleanupRetryRepository.read(context, null, 5_000L), 5_000L);
        PendingCleanupRetryRepository.remove(null, ENTRY);
        PendingCleanupRetryRepository.remove(context, null);
        assertFalse(PendingCleanupRetryRepository.recordFailure(null, ENTRY, 5_000L));
        assertFalse(PendingCleanupRetryRepository.recordFailure(context, null, 5_000L));
        for (String key : prefs.getAll().keySet()) {
            assertFalse("nothing may be stored for a missing entry: " + key,
                    key.startsWith(RECORD_KEY_PREFIX));
        }
    }

    private static void assertFresh(PendingCleanupRetryRepository.RetryState state, long now) {
        assertEquals(0, state.attempts);
        assertEquals("a fresh record is due immediately", now, state.nextAttemptAt);
        assertEquals("a fresh record expires 24h after now", now + DAY_MS, state.expiresAt);
    }
}
