package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #823: the dropdown menu of the KeepADB title bars. One look for two menus:
 * <ul>
 *   <li>the home menu behind the gear of the main view ({@link #forHome}, #940): Language, All
 *       settings, then every settings section in the order of the Settings screen, each with the
 *       icon of its section;</li>
 *   <li>the privacy menu behind a long press on any eye button ({@link KeepADBPrivacyToggle},
 *       #941): the four stored privacy switches as check entries and a link to the Privacy
 *       section.</li>
 * </ul>
 * Check entries flip their switch and leave the menu open; every other entry closes it.
 */
final class KeepADBSettingsMenu {

    static final int ID_LANGUAGE = 1;
    static final int ID_DIVIDER_1 = -1;
    static final int ID_ALL_SETTINGS = 3;
    static final int ID_DIVIDER_2 = -2;
    static final int ID_SETUP_ASSISTANT = 4;
    static final int ID_NETWORK = 5;
    static final int ID_WEBHOOK = 6;
    static final int ID_USB_ADB = 7;
    static final int ID_PRIVACY = 12;
    static final int ID_MISC = 8;
    static final int ID_DIAGNOSTICS = 13;

    static final class MenuItem {
        final int id;
        final int titleResId;
        final int iconResId;
        final boolean isDivider;
        final boolean isCheckable;

        MenuItem(int id, int titleResId, int iconResId, boolean isDivider, boolean isCheckable) {
            this.id = id;
            this.titleResId = titleResId;
            this.iconResId = iconResId;
            this.isDivider = isDivider;
            this.isCheckable = isCheckable;
        }

        static MenuItem link(int id, int titleResId, int iconResId) {
            return new MenuItem(id, titleResId, iconResId, false, false);
        }

        static MenuItem check(int id, int titleResId) {
            return new MenuItem(id, titleResId, 0, false, true);
        }

        static MenuItem divider(int id) {
            return new MenuItem(id, 0, 0, true, false);
        }
    }

    /** What a menu does with its entries. */
    interface Handler {
        /** Carries out {@code item}; check entries keep the menu open, others dismiss it. */
        void onItem(KeepADBSettingsMenu menu, MenuItem item);

        /** The stored state behind a check entry. */
        boolean isChecked(MenuItem item);
    }

    private final Activity activity;
    private final View anchorView;
    private final List<MenuItem> items;
    private final Handler handler;
    private ListPopupWindow popupWindow;
    private MenuAdapter adapter;

    KeepADBSettingsMenu(Activity activity, View anchorView, List<MenuItem> items, Handler handler) {
        this.activity = activity;
        this.anchorView = anchorView;
        this.items = items;
        this.handler = handler;
    }

    /** #940: the menu behind the gear of the main view. */
    static KeepADBSettingsMenu forHome(MainActivity activity, View anchorView) {
        return new KeepADBSettingsMenu(activity, anchorView, buildMenuItems(), new Handler() {
            @Override
            public void onItem(KeepADBSettingsMenu menu, MenuItem item) {
                menu.dismiss();
                openHomeTarget(activity, item.id);
            }

            @Override
            public boolean isChecked(MenuItem item) {
                return false;
            }
        });
    }

    static List<MenuItem> buildMenuItems() {
        List<MenuItem> menu = new ArrayList<>();
        menu.add(MenuItem.link(ID_LANGUAGE, R.string.menu_item_language, R.drawable.ic_language));
        menu.add(MenuItem.divider(ID_DIVIDER_1));
        menu.add(MenuItem.link(ID_ALL_SETTINGS, R.string.menu_item_all_settings,
                R.drawable.ic_settings_gear));
        menu.add(MenuItem.divider(ID_DIVIDER_2));
        menu.add(MenuItem.link(ID_SETUP_ASSISTANT, R.string.settings_onboarding_title,
                R.drawable.ic_settings_assistant));
        menu.add(MenuItem.link(ID_NETWORK, R.string.settings_section_network,
                R.drawable.ic_settings_network));
        menu.add(MenuItem.link(ID_WEBHOOK, R.string.settings_section_webhook,
                R.drawable.ic_settings_sync));
        menu.add(MenuItem.link(ID_USB_ADB, R.string.settings_section_usb_adb, R.drawable.ic_usb));
        menu.add(MenuItem.link(ID_PRIVACY, R.string.privacy_switches_title,
                R.drawable.ic_settings_privacy));
        menu.add(MenuItem.link(ID_MISC, R.string.settings_section_misc, R.drawable.ic_more_horiz));
        menu.add(MenuItem.link(ID_DIAGNOSTICS, R.string.settings_diagnostics_title,
                R.drawable.ic_settings_diagnostics));
        return Collections.unmodifiableList(menu);
    }

    private static void openHomeTarget(MainActivity activity, int id) {
        switch (id) {
            case ID_LANGUAGE:
                activity.showLanguageSelectionDialog();
                return;
            case ID_ALL_SETTINGS:
                activity.startActivity(new Intent(activity, SettingsActivity.class));
                return;
            case ID_SETUP_ASSISTANT:
                activity.openSetupAssistant();
                return;
            case ID_NETWORK:
                openSettingsAt(activity, SettingsActivity.EXTRA_FOCUS_NETWORK_CARD);
                return;
            case ID_WEBHOOK:
                openSettingsAt(activity, SettingsActivity.EXTRA_FOCUS_WEBHOOK);
                return;
            case ID_USB_ADB:
                openSettingsAt(activity, SettingsActivity.EXTRA_FOCUS_USB);
                return;
            case ID_PRIVACY:
                openSettingsAt(activity, SettingsActivity.EXTRA_FOCUS_PRIVACY);
                return;
            case ID_MISC:
                openSettingsAt(activity, SettingsActivity.EXTRA_FOCUS_MISC);
                return;
            case ID_DIAGNOSTICS:
                openSettingsAt(activity, SettingsActivity.EXTRA_FOCUS_DIAGNOSTICS);
                return;
            default:
        }
    }

    /** Opens Settings with {@code focusExtra} set, so the matching card opens and scrolls in. */
    static void openSettingsAt(Activity activity, String focusExtra) {
        activity.startActivity(new Intent(activity, SettingsActivity.class)
                .putExtra(focusExtra, true));
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
            adapter = new MenuAdapter(activity, items, handler);
            popupWindow.setAdapter(adapter);

            popupWindow.setOnItemClickListener((parent, view, position, id) ->
                    onMenuItemClicked(items.get(position)));
        }

        adapter.notifyDataSetChanged();
        int contentWidth = measureContentWidth(adapter, activity);
        float density = activity.getResources().getDisplayMetrics().density;
        int minWidth = Math.round(240 * density);
        int maxWidth = Math.max(minWidth, activity.getResources().getDisplayMetrics().widthPixels
                - Math.round(32 * density));
        int finalWidth = Math.min(maxWidth,
                Math.max(minWidth, contentWidth + Math.round(24 * density)));
        popupWindow.setContentWidth(finalWidth);

        popupWindow.show();
    }

    void onMenuItemClicked(MenuItem item) {
        if (item.isDivider) return;
        handler.onItem(this, item);
        if (item.isCheckable && adapter != null) adapter.notifyDataSetChanged();
    }

    private static int measureContentWidth(MenuAdapter adapter, Context context) {
        ViewGroup measureParent = null;
        int maxWidth = 0;
        View itemView = null;
        int itemType = 0;
        final int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(0,
                View.MeasureSpec.UNSPECIFIED);
        final int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0,
                View.MeasureSpec.UNSPECIFIED);
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

        private final Activity activity;
        private final List<MenuItem> items;
        private final Handler handler;
        private final LayoutInflater inflater;

        MenuAdapter(Activity activity, List<MenuItem> items, Handler handler) {
            this.activity = activity;
            this.items = items;
            this.handler = handler;
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
                    convertView = inflater.inflate(R.layout.item_settings_menu_divider, parent,
                            false);
                }
                return convertView;
            }

            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_settings_menu, parent, false);
            }

            ImageView icon = convertView.findViewById(R.id.menu_item_icon);
            TextView title = convertView.findViewById(R.id.menu_item_title);
            CheckBox checkbox = convertView.findViewById(R.id.menu_item_checkbox);

            if (item.iconResId != 0) {
                icon.setImageResource(item.iconResId);
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }
            title.setText(item.titleResId);

            if (item.isCheckable) {
                boolean enabled = handler.isChecked(item);
                checkbox.setVisibility(View.VISIBLE);
                checkbox.setChecked(enabled);
                String stateText = activity.getString(enabled
                        ? R.string.menu_item_state_on : R.string.menu_item_state_off);
                convertView.setContentDescription(activity.getString(item.titleResId) + ", "
                        + stateText);
            } else {
                checkbox.setVisibility(View.GONE);
                convertView.setContentDescription(null);
            }

            return convertView;
        }
    }
}
