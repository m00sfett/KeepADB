package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link KeepADBSettingsGateway} fake for #580: reproduces the OEM read restriction that
 * {@link KeepADBAndroidSettingsGateway}'s own javadoc already anticipates on top of the write
 * permission ({@code WRITE_SECURE_SETTINGS} grants the write, not necessarily the read). {@link
 * #write} behaves like {@link KeepADBFakeSettingsGateway} so a test can still exercise the write
 * path that sits around the failing read.
 *
 * <p>{@code alwaysThrow} controls whether every {@link #isEnabled} call fails or only the first
 * one. The latter isolates a single call site's own fallback from unrelated, still-unguarded
 * {@code isEnabled()} reads elsewhere in the call chain (see #582, filed for the ones #580
 * deliberately leaves untouched) -- with {@code alwaysThrow = false}, a later {@code isEnabled()}
 * call in the same test returns the real, write-tracked state instead of throwing again.
 */
final class KeepADBThrowingSettingsGateway implements KeepADBSettingsGateway {
    final List<Boolean> writes = new ArrayList<>();
    private final boolean alwaysThrow;
    private boolean hasThrownOnce;
    private boolean enabled;
    private boolean writeSuccess = true;

    KeepADBThrowingSettingsGateway() {
        this(true, false);
    }

    KeepADBThrowingSettingsGateway(boolean alwaysThrow, boolean initiallyEnabled) {
        this.alwaysThrow = alwaysThrow;
        this.enabled = initiallyEnabled;
    }

    void setWriteSuccess(boolean writeSuccess) {
        this.writeSuccess = writeSuccess;
    }

    @Override
    public boolean isEnabled(Context context) {
        if (alwaysThrow || !hasThrownOnce) {
            hasThrownOnce = true;
            throw new SecurityException("read restricted by OEM policy (test fake, #580)");
        }
        return enabled;
    }

    @Override
    public boolean write(Context appContext, boolean on) {
        writes.add(on);
        if (writeSuccess) {
            enabled = on;
        }
        return writeSuccess;
    }
}
