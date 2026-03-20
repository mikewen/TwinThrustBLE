# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified in the
# Android SDK's tools/proguard/proguard-android-optimize.txt

# Nordic BLE library
-keep class no.nordicsemi.android.ble.** { *; }

# Google Play Services
-keep class com.google.android.gms.** { *; }

# Keep BLE manager subclass
-keep class com.twinthrustble.AC6328BleManager { *; }
