package de.hohnepeople.keepadb;

import android.content.Context;
import android.provider.Settings;

/** Production {@link KeepADBSettingsGateway} backed by the real Settings.Global provider. */
final class KeepADBAndroidSettingsGateway implements KeepADBSettingsGateway {
    @Override
    public boolean isEnabled(Context context) {
        // AOSP permits applications to read this @Readable Settings.Global key, while the
        // corresponding write requires WRITE_SECURE_SETTINGS. Keep the recovery-pulse catch
        // defensive: an OEM or future provider may impose additional read restrictions and
        // throw SecurityException after accepting the write.
        return Settings.Global.getInt(context.getContentResolver(), KeepADB.KEY, 0) == 1;
    }

    @Override
    public boolean write(Context appContext, boolean on) {
        return Settings.Global.putInt(appContext.getContentResolver(), KeepADB.KEY, on ? 1 : 0);
    }
}
