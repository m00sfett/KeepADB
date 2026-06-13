# WiFi-ADB

Winzige Android-App zum Ein-/Ausschalten von **WLAN-ADB** (Androids „Wireless debugging")
direkt am Handy — per Schalter in der App, per Home-Widget und per Quick-Settings-Kachel.

Entstanden, weil WLAN-ADB auf dem Moosphone (Samsung SM-G780G, Android 13) keinen
Neustart überlebt. Mit dieser App genügt nach jedem Reboot ein Tipp, um es wieder zu aktivieren.

## Funktionsweise

- Ohne Root kann eine App **nicht** das klassische `adb tcpip 5555` (Systemeigenschaft) setzen.
- Stattdessen schaltet sie Androids modernes „Wireless debugging" über das geschützte Setting
  `Settings.Global.adb_wifi_enabled` (0/1).
- Dafür braucht die App die Berechtigung `WRITE_SECURE_SETTINGS`, die **einmalig** per USB
  vergeben wird (überlebt Reboots):

  ```
  adb shell pm grant de.moos.wifiadb android.permission.WRITE_SECURE_SETTINGS
  ```

- Der Schalterzustand selbst wird von Android beim Reboot zurückgesetzt → nach Neustart
  einmal Tile/Widget/App antippen.

## Drei Bedien-Oberflächen

1. **App** — eine Activity mit einem Schalter + Statusanzeige.
2. **Quick-Settings-Tile** — `AdbWifiTileService`. In den Schnelleinstellungen über „Bearbeiten"
   frei platzierbar (WLAN-ADB).
3. **Home-Widget** — `AdbWifiWidget`. Auf den Startbildschirm legbar, Tippen schaltet um.

## Build & Installation

Voraussetzungen am PC: Android SDK unter `~/Android/Sdk` (Platform 35, build-tools 35),
JDK 17 unter `/usr/lib/jvm/java-17-openjdk`.

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant de.moos.wifiadb android.permission.WRITE_SECURE_SETTINGS
```

## Technik

- Reines AOSP-Framework, **keine externen Dependencies** (kein androidx/Material).
- minSdk 30 (Android 11+, ab dem `adb_wifi_enabled` existiert), target/compileSdk 35, Java 17.
- Paket-ID `de.moos.wifiadb`.

## Verbinden vom PC (MoosGames2020)

Da es „Wireless debugging" ist (dynamischer Port + einmaliges Pairing), nicht 5555 — siehe
Vault-Notiz `System/WiFi-ADB-App.md`.
