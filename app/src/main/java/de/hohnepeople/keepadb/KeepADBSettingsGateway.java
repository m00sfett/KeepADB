package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * Boundary around the Settings.Global read/write KeepADB's toggle state machine depends on
 * (#248), so its debounce, generation-token, and recovery-pulse logic can be exercised in a
 * test against a fake instead of a real ContentResolver.
 */
interface KeepADBSettingsGateway {
    boolean isEnabled(Context context);

    boolean write(Context appContext, boolean on);
}
