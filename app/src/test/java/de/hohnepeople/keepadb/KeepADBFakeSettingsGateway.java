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
    private boolean writeSuccess = true;
    private boolean writeThrowsSecurityException;
    final List<Boolean> writes = new ArrayList<>();

    KeepADBFakeSettingsGateway(boolean initiallyEnabled) {
        this.enabled = initiallyEnabled;
    }

    void setWriteSuccess(boolean writeSuccess) {
        this.writeSuccess = writeSuccess;
    }

    /** Makes every write fail like a platform that revoked the grant behind the app's back. */
    void setWriteThrowsSecurityException(boolean writeThrowsSecurityException) {
        this.writeThrowsSecurityException = writeThrowsSecurityException;
    }

    @Override
    public boolean isEnabled(Context context) {
        return enabled;
    }

    @Override
    public boolean write(Context appContext, boolean on) {
        writes.add(on);
        if (writeThrowsSecurityException) {
            throw new SecurityException("WRITE_SECURE_SETTINGS refused by the platform");
        }
        if (!writeSuccess) {
            return false;
        }
        enabled = on;
        return true;
    }
}
