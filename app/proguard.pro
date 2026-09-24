# R8 rules for the release build (toolchain/build.sh --release).
#
# There is no Android Gradle Plugin here, which means nobody generated the usual
# "keep the manifest components" rules for us: R8 shrinks from the entry points it
# is told about, so without this file it would happily strip or rename the very
# classes Android instantiates from AndroidManifest.xml, and the app would crash
# with ClassNotFoundException on launch. These rules are the replacement for
# AGP's automatic keep rules - keep them in sync with the manifest.

# --- manifest components: Android instantiates these by name ----------------
-keep class damjay.control.ghosthand.MainActivity { *; }
-keep class damjay.control.ghosthand.HostActivity { *; }
-keep class damjay.control.ghosthand.GuestActivity { *; }
-keep class damjay.control.ghosthand.host.ScreenCaptureService { *; }

# Anything reached from the framework by name (listeners, callbacks) stays too.
# `-keepnames` on the package is a cheap safety net against renaming a class the
# platform or a saved instance state later refers to by name.
-keepnames class damjay.control.ghosthand.** { *; }

# --- AndroidX / Material: optional dependencies R8 cannot resolve -----------
# The library jar is a flat merge of 56 AARs; some of them reference classes from
# libraries that are not in the harvested set (Kotlin stdlib, Play services,
# profileinstaller internals). They are never called on the paths we use, so warn
# instead of failing the build.
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-dontwarn com.google.common.**
-dontwarn kotlin.**

# Keep the AppCompat/Material runtime entry points that are discovered
# reflectively: the AppCompat widget inflater looks up view classes by name from
# the XML, so their names must survive shrinking.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Activities/Services subclassed by AndroidX must keep their lifecycle methods.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# Serialised state (saved instance state, Parcelable CREATOR) uses reflection.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Attributes R8 needs to keep together. InnerClasses *requires* EnclosingMethod
# (R8 refuses to write one without the other: an anonymous class`InnerClasses`
# entry without the method it was declared in is not decodable). Signature keeps
# generics honest for the Java reflection the framework does on our listeners, and
# Exceptions is what lets a shrunk method still declare what it may throw.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
