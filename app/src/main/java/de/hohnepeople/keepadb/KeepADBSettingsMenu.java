package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #823: Dropdown menu opened by the header settings button on the main view.
 * Provides quick links (Language, Privacy mode with inline checkbox, All settings,
 * Setup assistant, Network, Webhook, USB-ADB, Other) while keeping the privacy toggle
 * inline and dismiss-free.
 */
final class KeepADBSettingsMenu {

    static final int ID_LANGUAGE = 1;
    static final int ID_PRIVACY_MODE = 2;
    static final int ID_DIVIDER_1 = -1;
    static final int ID_ALL_SETTINGS = 3;
    static final int ID_DIVIDER_2 = -2;
    static final int ID_SETUP_ASSISTANT = 4;
    static final int ID_NETWORK = 5;
    static final int ID_WEBHOOK = 6;
    static final int ID_USB_ADB = 7;
    static final int ID_MISC = 8;

    static final class MenuItem {
        final int id;
        final int titleResId;
        final boolean isDivider;
        final boolean isCheckable;

        MenuItem(int id, int titleResId) {
            this(id, titleResId, false, false);
        }

        MenuItem(int id, int titleResId, boolean isDivider, boolean isCheckable) {
            this.id = id;
            this.titleResId = titleResId;
            this.isDivider = isDivider;
            this.isCheckable = isCheckable;
        }

        static MenuItem divider(int id) {
            return new MenuItem(id, 0, true, false);
        }
    }

    private final MainActivity activity;
    private final View anchorView;
    private final List<MenuItem> items;
    private ListPopupWindow popupWindow;
    private MenuAdapter adapter;

    KeepADBSettingsMenu(MainActivity activity, View anchorView) {
        this.activity = activity;
        this.anchorView = anchorView;
        this.items = buildMenuItems();
    }

    static List<MenuItem> buildMenuItems() {
        List<MenuItem> menu = new ArrayList<>();
        menu.add(new MenuItem(ID_LANGUAGE, R.string.menu_item_language));
        menu.add(new MenuItem(ID_PRIVACY_MODE, R.string.menu_item_privacy_mode, false, true));
        menu.add(MenuItem.divider(ID_DIVIDER_1));
        menu.add(new MenuItem(ID_ALL_SETTINGS, R.string.menu_item_all_settings));
        menu.add(MenuItem.divider(ID_DIVIDER_2));
        menu.add(new MenuItem(ID_SETUP_ASSISTANT, R.string.settings_onboarding_title));
        menu.add(new MenuItem(ID_NETWORK, R.string.settings_section_network));
        menu.add(new MenuItem(ID_WEBHOOK, R.string.settings_section_webhook));
        menu.add(new MenuItem(ID_USB_ADB, R.string.settings_section_usb_adb));
        menu.add(new MenuItem(ID_MISC, R.string.settings_section_misc));
        return Collections.unmodifiableList(menu);
    }

    List<MenuItem> getItems() {
        return items;
    }

    ListPopupWindow getPopupWindow() {
        return popupWindow;
    }

    MenuAdapter getAdapter() {
        return adapter;
    }

    boolean isShowing() {
        return popupWindow != null && popupWindow.isShowing();
    }

    void dismiss() {
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }

    void show() {
        if (popupWindow == null) {
            popupWindow = new ListPopupWindow(activity);
            popupWindow.setAnchorView(anchorView);
            popupWindow.setModal(true);
            popupWindow.setDropDownGravity(Gravity.END);
            popupWindow.setBackgroundDrawable(activity.getDrawable(R.drawable.bg_menu_popup));
            adapter = new MenuAdapter(activity, items);
            popupWindow.setAdapter(adapter);

            popupWindow.setOnItemClickListener((parent, view, position, id) -> {
                MenuItem item = items.get(position);
                onMenuItemClicked(item);
            });
        }

        adapter.notifyDataSetChanged();
        int contentWidth = measureContentWidth(adapter, activity);
        float density = activity.getResources().getDisplayMetrics().density;
        int minWidth = Math.round(240 * density);
        int maxWidth = Math.max(minWidth, activity.getResources().getDisplayMetrics().widthPixels - Math.round(32 * density));
        int finalWidth = Math.min(maxWidth, Math.max(minWidth, contentWidth + Math.round(24 * density)));
        popupWindow.setContentWidth(finalWidth);

        popupWindow.show();
    }

    void onMenuItemClicked(MenuItem item) {
        if (item.isDivider) return;

        if (item.id == ID_PRIVACY_MODE) {
            boolean want = !KeepADBPreferences.isPrivacyModeEnabled(activity);
            KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable", "privacy_mode_toggle");
            KeepADBPreferences.setPrivacyModeEnabled(activity, want);
            KeepADBEndpointCoordinator.refresh(activity);
            KeepADBTileService.requestRefresh(activity);
            activity.refreshUiAndComponents();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }

        dismiss();

        switch (item.id) {
            case ID_LANGUAGE:
                activity.showLanguageSelectionDialog();
                break;
            case ID_ALL_SETTINGS:
                activity.startActivity(new Intent(activity, SettingsActivity.class));
                break;
            case ID_SETUP_ASSISTANT:
                activity.openSetupAssistant();
                break;
            case ID_NETWORK: {
                Intent intent = new Intent(activity, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_FOCUS_NETWORK_CARD, true);
                activity.startActivity(intent);
                break;
            }
            case ID_WEBHOOK: {
                Intent intent = new Intent(activity, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true);
                activity.startActivity(intent);
                break;
            }
            case ID_USB_ADB: {
                Intent intent = new Intent(activity, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_FOCUS_USB, true);
                activity.startActivity(intent);
                break;
            }
            case ID_MISC: {
                Intent intent = new Intent(activity, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_FOCUS_MISC, true);
                activity.startActivity(intent);
                break;
            }
            default:
                break;
        }
    }

    private static int measureContentWidth(MenuAdapter adapter, Context context) {
        ViewGroup measureParent = null;
        int maxWidth = 0;
        View itemView = null;
        int itemType = 0;
        final int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        final int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        final int count = adapter.getCount();
        for (int i = 0; i < count; i++) {
            final int positionType = adapter.getItemViewType(i);
            if (positionType != itemType) {
                itemType = positionType;
                itemView = null;
            }
            if (measureParent == null) {
                measureParent = new FrameLayout(context);
            }
            itemView = adapter.getView(i, itemView, measureParent);
            itemView.measure(widthMeasureSpec, heightMeasureSpec);
            final int itemWidth = itemView.getMeasuredWidth();
            if (itemWidth > maxWidth) {
                maxWidth = itemWidth;
            }
        }
        return maxWidth;
    }

    static final class MenuAdapter extends BaseAdapter {
        private static final int VIEW_TYPE_ITEM = 0;
        private static final int VIEW_TYPE_DIVIDER = 1;

        private final MainActivity activity;
        private final List<MenuItem> items;
        private final LayoutInflater inflater;

        MenuAdapter(MainActivity activity, List<MenuItem> items) {
            this.activity = activity;
            this.items = items;
            this.inflater = LayoutInflater.from(activity);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public MenuItem getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).isDivider ? VIEW_TYPE_DIVIDER : VIEW_TYPE_ITEM;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return !items.get(position).isDivider;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            MenuItem item = items.get(position);
            if (item.isDivider) {
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.item_settings_menu_divider, parent, false);
                }
                return convertView;
            }

            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_settings_menu, parent, false);
            }

            TextView title = convertView.findViewById(R.id.menu_item_title);
            CheckBox checkbox = convertView.findViewById(R.id.menu_item_checkbox);

            title.setText(item.titleResId);

            if (item.isCheckable) {
                boolean enabled = KeepADBPreferences.isPrivacyModeEnabled(activity);
                checkbox.setVisibility(View.VISIBLE);
                checkbox.setChecked(enabled);
                String stateText = activity.getString(enabled
                        ? R.string.privacy_toggle_disable_accessibility
                        : R.string.privacy_toggle_enable_accessibility);
                convertView.setContentDescription(activity.getString(item.titleResId) + ", " + stateText);
            } else {
                checkbox.setVisibility(View.GONE);
                convertView.setContentDescription(null);
            }

            return convertView;
        }
    }
}
