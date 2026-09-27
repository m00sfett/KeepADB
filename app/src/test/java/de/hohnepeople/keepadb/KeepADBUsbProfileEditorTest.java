package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Behavior tests for {@link KeepADBUsbProfileEditor} in isolation from {@link SettingsActivity}'s
 * own lifecycle. {@link SettingsActivityTest#profileEditDraftSavesAndRestoresAcrossRecreation()}
 * and {@link SettingsActivityTest#dialogsDismissOnDestroyToPreventWindowAndContextLeaks()} already
 * cover the end-to-end wiring through a real activity recreation; these pin the editor's own
 * {@link Bundle} contract and cleanup behavior directly, on a bare {@link Activity} the editor
 * knows nothing about beyond its {@link android.content.Context} surface -- proof that a new
 * profile field only needs to touch this one class, not {@code SettingsActivity} itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBUsbProfileEditorTest {

    @Test
    public void draftFieldsSurviveASaveStateRestoreRoundtripOnAFreshEditorInstance() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int[] changeCount = {0};
        KeepADBUsbProfileEditor editor = new KeepADBUsbProfileEditor(activity, () -> changeCount[0]++);

        editor.showDialog(KeepADBUsbNotification.ACTION_CREATE);
        AlertDialog dialog = editor.getActiveEditDialog();
        assertNotNull("Creating with no existing profiles must open the edit dialog directly", dialog);
        assertTrue(dialog.isShowing());

        List<EditText> fields = findViewsByType(dialog.getWindow().getDecorView(), EditText.class);
        assertEquals(4, fields.size());
        fields.get(0).setText("Draft-Host");
        fields.get(1).setText("10.1.2.3");
        fields.get(2).setText("draft.local");
        fields.get(3).setText("draft.tailnet");

        Bundle saved = new Bundle();
        editor.saveState(saved);
        assertTrue(saved.getBoolean(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_SHOWING));
        assertEquals(-1, saved.getInt(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_ID));
        assertEquals("Draft-Host", saved.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_NAME));
        assertEquals("10.1.2.3", saved.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_IP));
        assertEquals("draft.local", saved.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_HOSTNAME));
        assertEquals("draft.tailnet", saved.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_TAILNET));

        // A brand new editor instance, standing in for the fresh instance SettingsActivity#onCreate
        // creates on every real recreation -- no shared state with the editor above.
        KeepADBUsbProfileEditor restored = new KeepADBUsbProfileEditor(activity, () -> { });
        restored.restore(saved);
        AlertDialog restoredDialog = restored.getActiveEditDialog();
        assertNotNull("A saved-showing draft must reopen the edit dialog on restore", restoredDialog);
        assertTrue(restoredDialog.isShowing());

        List<EditText> restoredFields = findViewsByType(restoredDialog.getWindow().getDecorView(), EditText.class);
        assertEquals("Draft-Host", restoredFields.get(0).getText().toString());
        assertEquals("10.1.2.3", restoredFields.get(1).getText().toString());
        assertEquals("draft.local", restoredFields.get(2).getText().toString());
        assertEquals("draft.tailnet", restoredFields.get(3).getText().toString());

        assertEquals("Neither the draft nor its restore must fire the profile-changed callback",
                0, changeCount[0]);
    }

    @Test
    public void restoreWithNoSavedDraftShowsNoDialogAndTakesNullSafely() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        KeepADBUsbProfileEditor editor = new KeepADBUsbProfileEditor(activity, () -> { });

        editor.restore(new Bundle());
        assertNull(editor.getActiveEditDialog());

        editor.restore(null);
        assertNull(editor.getActiveEditDialog());
    }

    @Test
    public void saveStateWritesNothingWhenNoEditDialogIsShowing() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        KeepADBUsbProfileEditor editor = new KeepADBUsbProfileEditor(activity, () -> { });

        Bundle outState = new Bundle();
        editor.saveState(outState);
        assertFalse(outState.containsKey(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_SHOWING));
    }

    @Test
    public void destroyDismissesAShowingEditDialogAndDropsTheReference() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        KeepADBUsbProfileEditor editor = new KeepADBUsbProfileEditor(activity, () -> { });

        editor.showDialog(KeepADBUsbNotification.ACTION_CREATE);
        AlertDialog dialog = editor.getActiveEditDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());

        editor.destroy();
        assertFalse("destroy() must dismiss a still-showing dialog to avoid a window leak",
                dialog.isShowing());
        assertNull("destroy() must drop the active dialog reference",
                editor.getActiveEditDialog());
    }

    private static <T extends View> List<T> findViewsByType(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        findViewsByTypeInternal(root, type, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void findViewsByTypeInternal(View root, Class<T> type, List<T> result) {
        if (root == null) return;
        if (type.isInstance(root)) {
            result.add((T) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                findViewsByTypeInternal(group.getChildAt(i), type, result);
            }
        }
    }
}
