package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Host of the "Trust this network?" decision (#766): the tap target of the "new Wi-Fi" prompt
 * notification (both forms, #759). A dialog-themed activity of its own, so the user decides on top
 * of the app they came from and lands there again, not in the Settings.
 *
 * <p><strong>Never over the lock screen.</strong> The activity declares neither {@code
 * showWhenLocked} nor {@code turnScreenOn} (asserted by the manifest contract test), so Android
 * asks for the unlock before the notification can open it. On top of that, this class checks the
 * keyguard itself: while it is showing, nothing is bound and the view holds no name or address;
 * the name is bound only once the device is unlocked, and removed again when the activity stops
 * (screen off, another app). The prompt notification stays neutral on the lock screen as before
 * (#578/#592/#598); the name shown here is the deliberate exception of the privacy mode for the
 * decision itself.
 *
 * <p>The intent carries only a BSSID, as a selector: {@link KeepADBNetworkDecision#resolve} reads
 * the name and the question's state from the app's own record, so the activity can neither be made
 * to show a name nor to answer for an access point the app never recorded. Back, a tap outside and
 * "decide later" finish the activity without writing anything.
 */
public class NetworkDecisionActivity extends Activity {

    private NetworkDecisionView decisionView;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_decision);
        // Outside the dialog counts as "decide later": it finishes and decides nothing.
        setFinishOnTouchOutside(true);
        decisionView = findViewById(R.id.network_decision);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Resumed while the keyguard was still up and unlocked afterwards without a new resume.
        if (hasFocus && !decisionView.isBound()) {
            render();
        }
    }

    @Override
    protected void onStop() {
        // Screen off, lock or another app in front: keep no name in the view hierarchy.
        decisionView.clear();
        super.onStop();
    }

    /**
     * Binds the prompted access point if the device is unlocked, and ends the activity with a
     * short message when there is nothing left to decide. Called on every resume, so the state is
     * never older than the moment the user sees it.
     */
    private void render() {
        if (isFinishing()) return;
        if (isKeyguardLocked()) {
            decisionView.clear();
            return;
        }
        KeepADBNetworkDecision.Resolution resolution = KeepADBNetworkDecision.resolve(this,
                getIntent().getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
        switch (resolution.status) {
            case PENDING:
                decisionView.bind(resolution.pending, new NetworkDecisionView.Listener() {
                    @Override
                    public void onDecided(KeepADBNetworkDecision.Outcome outcome) {
                        finish();
                    }

                    @Override
                    public void onDecideLater() {
                        finish();
                    }
                });
                break;
            case ALREADY_DECIDED:
                // The tap is only an entry point to a question; there is none left to ask.
                KeepADBDiagnostics.event(this, "user_action", "network_trust_prompt", "skipped",
                        "already_decided");
                // #798: besides the message, show where the decision can be seen and changed:
                // the Networks list, on the row of this access point (#799).
                startActivity(NetworkListActivity.intent(this,
                        getIntent().getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID)));
                endWith(R.string.network_decision_already_decided_toast);
                break;
            default:
                KeepADBDiagnostics.event(this, "user_action", "network_trust_prompt", "skipped",
                        "confirmation_not_pending");
                endWith(R.string.network_decision_expired_toast);
                break;
        }
    }

    private void endWith(int messageRes) {
        decisionView.clear();
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show();
        finish();
    }

    private boolean isKeyguardLocked() {
        KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        // Fail closed: without a KeyguardManager the lock state is unknown, so nothing is shown.
        return keyguardManager == null || keyguardManager.isKeyguardLocked();
    }
}
