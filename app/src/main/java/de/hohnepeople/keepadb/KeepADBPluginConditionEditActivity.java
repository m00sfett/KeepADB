package de.hohnepeople.keepadb;

/**
 * #999: the edit screen of the Tasker/Locale condition plugin ("Wifi-ADB is on", "endpoint
 * known"). It is the action edit screen in condition mode, with the same caller checks; it is a
 * class of its own because the Locale API identifies a plugin by its edit activity, and
 * {@code REQUEST_QUERY} names this class.
 */
public class KeepADBPluginConditionEditActivity extends KeepADBPluginEditActivity {
    @Override
    boolean conditionMode() {
        return true;
    }
}
