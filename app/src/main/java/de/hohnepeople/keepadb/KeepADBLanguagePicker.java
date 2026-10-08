package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;

/** The shared native language picker, hosted by the current page. */
final class KeepADBLanguagePicker {
    private KeepADBLanguagePicker() { }

    static AlertDialog create(Activity activity) {
        KeepADBLocaleHelper.LanguageItem[] languages = KeepADBLocaleHelper.SUPPORTED_LANGUAGES;
        String[] displayItems = new String[languages.length];
        String currentTag = KeepADBLocaleHelper.getSelectedLanguageTag(activity);
        int selectedIndex = 0;

        for (int i = 0; i < languages.length; i++) {
            if (languages[i].tag.isEmpty()) {
                displayItems[i] = activity.getString(R.string.settings_language_system_default);
            } else {
                displayItems[i] = languages[i].endonym;
            }
            if (languages[i].tag.equalsIgnoreCase(currentTag)) {
                selectedIndex = i;
            }
        }

        AlertDialog picker = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_language_dialog_title)
                .setSingleChoiceItems(displayItems, selectedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    String chosenTag = languages[which].tag;
                    KeepADBLocaleHelper.setAppLanguage(activity, chosenTag);
                    KeepADBWidget.refreshAll(activity);
                    KeepADBEndpointCoordinator.refresh(activity);
                    KeepADBUsbReceiver.refresh(activity);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        picker.setOwnerActivity(activity);
        return picker;
    }
}
