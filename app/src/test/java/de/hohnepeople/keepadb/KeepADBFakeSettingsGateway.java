package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link KeepADBSettingsGateway} fake with a write log, so a test can assert the
 * final applied on/off state and the exact sequence of writes without a real ContentResolver
 * (#249).
 */
final class KeepADBFakeSettingsGateway implements KeepADBSettingsGateway {
    private boolean enabled;
    final List<Boolean> writes = new ArrayList<>();

    KeepADBFakeSettingsGateway(boolean initiallyEnabled) {
        this.enabled = initiallyEnabled;
    }

    @Override
    public boolean isEnabled(Context context) {
        return enabled;
    }

    @Override
    public boolean write(Context appContext, boolean on) {
        enabled = on;
        writes.add(on);
        return true;
    }
}
