# AGENTS.md — WiFi-ADB

Android-App zum Schalten von WLAN-ADB (Wireless debugging) am Moosphone.
Übergeordnete Regeln: siehe `~/AGENTS.md` (MoosGames2020).

## Kontext

- Gerät: Samsung SM-G780G (Galaxy S20 FE), Android 13 (SDK 33), **kein Root**.
- Problem gelöst: WLAN-ADB überlebt keinen Reboot → App liefert schnellen Wieder-Einschalter.
- Mechanismus: `Settings.Global.adb_wifi_enabled` 0/1, App hält `WRITE_SECURE_SETTINGS`
  (einmalig per `adb shell pm grant` vergeben, überlebt Reboots).

## Konventionen

- Reines AOSP-Framework, keine externen Dependencies. KISS — nicht overengineeren.
- Paket-ID `de.moos.wifiadb`. minSdk 30, target/compileSdk 35, Java 17.
- Drei Oberflächen teilen sich die Logik in `AdbWifi.java` (isEnabled/setEnabled).
- Jede Oberfläche liest den Zustand live; kein persistenter App-State.

## Build

- SDK: `~/Android/Sdk` (Platform 35, build-tools 35). JDK 17: `/usr/lib/jvm/java-17-openjdk`.
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- Installieren: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Test (ohne UI, da Handy oft gesperrt)

Den nicht-exportierten Widget-Receiver kann die adb-Shell nicht ansprechen. Stattdessen
über den Tile testen:

```bash
adb shell cmd statusbar add-tile de.moos.wifiadb/.AdbWifiTileService
adb shell cmd statusbar click-tile de.moos.wifiadb/.AdbWifiTileService   # ggf. 2s warten zwischen Klicks
adb shell settings get global adb_wifi_enabled                            # muss 0<->1 wechseln
adb shell cmd statusbar remove-tile de.moos.wifiadb/.AdbWifiTileService
```
