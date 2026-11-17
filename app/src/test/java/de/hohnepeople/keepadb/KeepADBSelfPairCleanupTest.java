package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;

import de.hohnepeople.keepadb.KeepADBSelfPairCleanup.Outcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

/**
 * #962: who may switch Wifi-ADB off after a grant on the phone. Each test names the rule of the
 * claim it holds; the scenarios are the ones the review of the first version found broken.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBSelfPairCleanupTest {
    private Context context;
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_selfpair", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        grant(false);
        KeepADBSelfPairCleanup.authorize("run-a");
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_selfpair", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private void grant(boolean granted) {
        if (granted) {
            shadowOf((Application) context)
                    .grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        } else {
            shadowOf((Application) context)
                    .denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        }
    }

    @Test
    public void theOwningRunSwitchesOffAndReleasesTheClaim() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);

        assertEquals(Outcome.SWITCHED_OFF, KeepADBSelfPairCleanup.settle(context, "run-a"));
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void withoutAClaimAGrantedPermissionSwitchesNothing() {
        // The attempt never paired, the permission came from a computer: not ours to clean up.
        grant(true);

        assertEquals(Outcome.LEFT_ON, KeepADBSelfPairCleanup.settle(context, "run-a"));
        KeepADBSelfPairCleanup.reconcile(context);
        assertTrue("Wifi-ADB stays as the user left it", gateway.isEnabled(context));
        assertTrue("no write was attempted", gateway.writes.isEmpty());
    }

    @Test
    public void anotherRunsClaimIsNotActedOnAndNotConsumed() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);

        assertEquals(Outcome.LEFT_ON, KeepADBSelfPairCleanup.settle(context, "run-b"));
        assertTrue(gateway.isEnabled(context));
        assertTrue("run A can still settle", KeepADBSelfPairCleanup.hasClaim(context));
        assertEquals(Outcome.SWITCHED_OFF, KeepADBSelfPairCleanup.settle(context, "run-a"));
    }

    @Test
    public void aNegativeCheckKeepsTheClaimForALateGrant() {
        KeepADBSelfPairCleanup.claim(context, "run-a");

        assertEquals(Outcome.NOT_GRANTED, KeepADBSelfPairCleanup.settle(context, "run-a"));
        assertTrue("still claimed", KeepADBSelfPairCleanup.hasClaim(context));

        // adbd ran the command after all; the process died meanwhile and starts again.
        grant(true);
        KeepADBSelfPairCleanup.reconcile(context);
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void aRefusedWriteKeepsTheClaimSoTheNextEntryTriesAgain() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);
        gateway.setWriteSuccess(false);

        assertEquals(Outcome.STILL_ON, KeepADBSelfPairCleanup.settle(context, "run-a"));
        assertTrue(gateway.isEnabled(context));
        assertTrue(KeepADBSelfPairCleanup.hasClaim(context));

        gateway.setWriteSuccess(true);
        KeepADBSelfPairCleanup.reconcile(context);
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void keepAliveLeavesWifiAdbOnAndReleasesTheClaim() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        // Set underneath the preference API, which itself would void the claim.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("keep_alive_enabled", true).commit();
        assertTrue(KeepADBPreferences.isKeepAliveEnabled(context));
        grant(true);

        assertEquals(Outcome.LEFT_ON, KeepADBSelfPairCleanup.settle(context, "run-a"));
        assertTrue(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void theClaimExpiresOnTheMonotonicClock() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);
        ShadowSystemClock.advanceBy(Duration.ofMillis(KeepADBSelfPairCleanup.WINDOW_MS + 1));

        KeepADBSelfPairCleanup.reconcile(context);
        assertTrue("too late to switch anything off", gateway.isEnabled(context));
        assertFalse("an expired claim is dropped", KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void aClaimFromBeforeARebootIsVoid() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 8);

        KeepADBSelfPairCleanup.reconcile(context);
        assertTrue(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void aManualToggleInTheAppVoidsTheClaim() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);

        // The user deliberately switches Wifi-ADB on in KeepADB.
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));

        KeepADBSelfPairCleanup.reconcile(context);
        assertEquals(Outcome.LEFT_ON, KeepADBSelfPairCleanup.settle(context, "run-a"));
        assertTrue("nothing is switched off behind the user's back", gateway.isEnabled(context));
    }

    @Test
    public void aKeepAliveDecisionVoidsTheClaimEvenIfTakenBack() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));

        KeepADBSelfPairCleanup.reconcile(context);
        assertTrue(gateway.isEnabled(context));
    }

    @Test
    public void theCleanupItselfDoesNotVoidItsOwnClaimBeforeItIsConfirmed() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);
        gateway.setWriteSuccess(false);

        KeepADBSelfPairCleanup.settle(context, "run-a");
        assertTrue("the self-pairing source is not a user decision",
                KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void onlyTheCurrentRunMayRecordAClaim() {
        // Run A is still finishing its pairing when run B begins and pairs.
        KeepADBSelfPairCleanup.authorize("run-b");
        assertEquals(KeepADBSelfPairCleanup.Claim.HELD,
                KeepADBSelfPairCleanup.claim(context, "run-b"));

        // Now A's pairing answer arrives. It must not take the cleanup away from B.
        assertEquals(KeepADBSelfPairCleanup.Claim.OBSOLETE,
                KeepADBSelfPairCleanup.claim(context, "run-a"));
        grant(true);
        assertEquals(Outcome.LEFT_ON, KeepADBSelfPairCleanup.settle(context, "run-a"));
        assertEquals("B still owns the cleanup and does it", Outcome.SWITCHED_OFF,
                KeepADBSelfPairCleanup.settle(context, "run-b"));
    }

    @Test
    public void aUserDecisionDuringPairingKeepsTheRunFromClaimingAfterwards() {
        // Pairing is under way; the user switches Keep-Alive on and off again in the app.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setKeepAliveEnabled(context, false);

        assertEquals("the grant may go on, the cleanup is no longer this run's",
                KeepADBSelfPairCleanup.Claim.SUPERSEDED,
                KeepADBSelfPairCleanup.claim(context, "run-a"));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
        grant(true);
        assertEquals(Outcome.LEFT_ON, KeepADBSelfPairCleanup.settle(context, "run-a"));
        assertTrue(gateway.isEnabled(context));
    }

    @Test
    public void withoutAKnownBootNoClaimIsRecorded() {
        Settings.Global.putString(context.getContentResolver(), Settings.Global.BOOT_COUNT, null);

        assertEquals("no claim that a reboot could not invalidate",
                KeepADBSelfPairCleanup.Claim.FAILED,
                KeepADBSelfPairCleanup.claim(context, "run-a"));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void aClaimWhoseBootBecameUnreadableIsVoid() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        grant(true);
        Settings.Global.putString(context.getContentResolver(), Settings.Global.BOOT_COUNT, null);

        KeepADBSelfPairCleanup.reconcile(context);
        assertTrue(gateway.isEnabled(context));
    }

    @Test
    public void aFailingCheckIsContainedAndChangesNothing() {
        KeepADBSelfPairCleanup.claim(context, "run-a");
        Context broken = new ContextWrapper(context) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public int checkSelfPermission(String permission) {
                throw new IllegalStateException("simulated platform failure");
            }
        };

        assertEquals(Outcome.UNKNOWN, KeepADBSelfPairCleanup.settle(broken, "run-a"));
        KeepADBSelfPairCleanup.reconcile(broken);
        assertTrue(gateway.isEnabled(context));
        assertTrue("an unresolved claim is kept", KeepADBSelfPairCleanup.hasClaim(context));
    }
}
