package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one Networks list behind the Network card in {@link SettingsActivity} (#654, #762, #769):
 * the current network on top and the saved ones grouped by Wi-Fi name, each with one status,
 * drawn by {@link NetworkListRenderer} from {@link KeepADBNetworkList}. The three older views
 * (allowed access points, recently prevented re-enabling, observed access points) were removed
 * with #769; this activity keeps only the shell: the header with the way back and the privacy eye,
 * the intro and the redraw on every resume.
 *
 * <p>Names and addresses follow the privacy mode through {@link KeepADBNetworkDisplay}. Trusting
 * or blocking here never switches Wireless Debugging on.
 */
public class NetworkListActivity extends Activity {
    private NetworkListRenderer networks;

    static Intent intent(Context context) {
        return new Intent(context, NetworkListActivity.class);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onDestroy() {
        if (networks != null) {
            networks.dismissDialog();
        }
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_list);
        KeepADBWindowInsets.apply(
                getWindow(),
                findViewById(R.id.header_bar),
                findViewById(R.id.network_list_scroll));

        ((TextView) findViewById(R.id.network_list_title)).setText(R.string.networks_title);
        ((TextView) findViewById(R.id.network_list_intro)).setText(R.string.networks_intro);
        LinearLayout networksRoot = findViewById(R.id.networks_root);
        networks = new NetworkListRenderer(this, networksRoot, this::render);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // #725: same eye as on the main view; redraws the masked list at once.
        KeepADBPrivacyToggle.bind(this, this::render);
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepADBPrivacyToggle.update(this);
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NetworkListRenderer.REQUEST_LOCATION) {
            // Re-read from the platform; the result arrays are not trusted (they can be empty).
            render();
        }
    }

    private void render() {
        // The list says it itself when the privacy mode hides it.
        networks.render();
    }
}
