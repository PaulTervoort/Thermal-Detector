-dontwarn org.jetbrains.annotations.CheckReturnValue
-dontwarn com.google.gson.annotations.Expose

# Keep class names for logging
-keepnames class nl.paultervoort.thermaldetector.**

# Do not modify SDK library classes because they interface with native code
-keepclassmembers,includedescriptorclasses class com.flir.thermalsdk.** { *; }

# Prevent native thermal SDK library classes used by this project from being excluded
-keep class com.flir.thermalsdk.ThermalSdk
-keep class com.flir.thermalsdk.androidsdk.live.connectivity.ConnectorFactoryAndroidHelper
-keep class com.flir.thermalsdk.androidsdk.live.connectivity.integrated.ConnectorFactoryAndroidIntegratedHelper
-keep class com.flir.thermalsdk.androidsdk.live.discovery.ScannerFactoryAndroidHelper
-keep class com.flir.thermalsdk.androidsdk.live.importing.ImporterFactoryAndroidHelper
-keep class com.flir.thermalsdk.image.AdeSettings
-keep class com.flir.thermalsdk.image.DdeSettings
-keep class com.flir.thermalsdk.image.EntropySettings
-keep class com.flir.thermalsdk.image.SignalLinearSettings
-keep class com.flir.thermalsdk.image.TemperatureLinearSettings
-keep class com.flir.thermalsdk.live.AuthenticationFileStorage
-keep class com.flir.thermalsdk.live.connectivity.CppVisualChannelListener
-keep class com.flir.thermalsdk.live.discovery.CppDiscoveryEventListener
