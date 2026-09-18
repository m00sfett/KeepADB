package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link KeepADBSettingsGateway} fake for #496: every write is accepted (returns {@code true})
 * but the readback never reflects it -- {@link #isEnabled} always answers {@code false},
 * regardless of what was just written. This is exactly the failure mode the issue is about: the
 * real Android "always allow Wireless Debugging on this network" pairing dialog was never
 * confirmed, so {@code Settings.Global.putInt} is accepted by the call but reverted by the OS.
 * {@link KeepADBFakeSettingsGateway} cannot simulate this -- its {@code write()} always updates
 * its own {@code enabled} flag on success, which is exactly the coupling this fake breaks.
 */
final class KeepADBStuckOffSettingsGateway implements KeepADBSettingsGateway {
    final List<Boolean> writes = new ArrayList<>();

    @Override
    public boolean isEnabled(Context context) {
        return false;
    }

    @Override
    public boolean write(Context appContext, boolean on) {
        writes.add(on);
        return true;
    }
}
