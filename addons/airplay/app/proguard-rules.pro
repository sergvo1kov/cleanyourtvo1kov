# ProGuard rules for PhairPlay release builds.
# Keep rules prevent ProGuard from removing or renaming code that is used
# via reflection or that must retain its original name for the AirPlay protocol.

# Flick AirPlay: Bouncy Castle is used only through its lightweight API by direct reference (no JCA
# provider, nothing loaded by name), so R8 may drop the rest of it.
-dontwarn org.bouncycastle.**

# Keep Timber — logging classes
-keep class timber.log.** { *; }

# Kotlin coroutines ship their own consumer rules (dispatcher factories loaded by ServiceLoader).
-dontwarn kotlinx.coroutines.**

# Keep our main application classes
-keep class com.phairplay.** { *; }

# General Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
