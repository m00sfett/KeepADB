# R8 configuration for the release build (#251).
#
# KeepADB has zero runtime dependencies, so R8's shrinker mainly removes unused platform-API
# wrapper code; the default proguard-android-optimize.txt rules (referenced from build.gradle)
# cover standard Android framework needs. AGP also automatically keeps every class the merged
# AndroidManifest.xml references (activities, services, receivers) without needing explicit
# rules here -- the -keep lines below are added anyway, purely as self-documentation of the
# app's user-visible entry points, so a future change that accidentally makes one of them
# unreachable from the manifest is still protected.
-keep class de.hohnepeople.keepadb.MainActivity
-keep class de.hohnepeople.keepadb.SettingsActivity
-keep class de.hohnepeople.keepadb.KeepADBService
-keep class de.hohnepeople.keepadb.KeepADBTileService
-keep class de.hohnepeople.keepadb.KeepADBWidget
-keep class de.hohnepeople.keepadb.BootReceiver
-keep class de.hohnepeople.keepadb.KeepADBUsbReceiver
-keep class de.hohnepeople.keepadb.KeepADBReceiver

# Keep line numbers in stack traces for crash reports gathered via the in-app issue reporter,
# without keeping full original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
