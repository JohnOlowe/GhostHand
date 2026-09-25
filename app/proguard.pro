# R8 rules for the release build (toolchain/build.sh --release).
#
# There is no Android Gradle Plugin here, which means nobody generated the usual
# "keep the manifest components" rules for us: R8 shrinks from the entry points it
# is told about, so without this file it would happily strip or rename the very
# classes Android instantiates from AndroidManifest.xml, and the app would crash
# with ClassNotFoundException on launch. These rules are the replacement for
# AGP's automatic keep rules - keep them in sync with the manifest.

# --- framework-instantiated classes -----------------------------------------
#
# These are the classes Android itself creates, by name, from AndroidManifest.xml.
# R8's call graph cannot see that happening, so it does not even know they are
# instantiated - and that has a consequence beyond the obvious one:
#
#   R8 keeps a method that *overrides a library method* only for classes it knows
#   can be instantiated. A class only the framework instantiates looks uninstantiated,
#   so its lifecycle overrides are dead code to R8, so they are deleted, so any state
#   they set looks like it is never set, and R8 can then delete the *readers* too.
#
# That cascade is not hypothetical: the accessibility service below was reduced to its
# two static methods by exactly this chain (onServiceConnected removed ->
# "instance" could never be non-null -> dispatchGesture unreachable -> whole injection
# path gone), and only the release build was affected. toolchain/../verify_apk.py now
# checks the built APK for the casualties, but the fix is here: keep these classes whole.
-keep class damjay.control.ghosthand.MainActivity { *; }
-keep class damjay.control.ghosthand.HostActivity { *; }
-keep class damjay.control.ghosthand.GuestActivity { *; }
-keep class damjay.control.ghosthand.host.ScreenCaptureService { *; }
-keep class damjay.control.ghosthand.host.InjectionAccessibilityService { *; }

# The anonymous GestureResultCallback inside the accessibility service is a separate
# class; -keep on the outer class does not cover it.
-keep class damjay.control.ghosthand.host.InjectionAccessibilityService$* { *; }

# Anything reached from the framework by name (listeners, callbacks) stays too.
# `-keepnames` on the package is a cheap safety net against renaming a class the
# platform or a saved instance state later refers to by name.
-keepnames class damjay.control.ghosthand.** { *; }

# --- Classes that are referenced but deliberately absent --------------------
#
# R8 fails the build on a missing class, and that is deliberate: it is the check that
# would have caught the Kotlin runtime hole that shipped in the first published APK.
# (There used to be a `-dontwarn kotlin.**` here, with a comment claiming those
# references were "never called on the paths we use". They were: AppCompatActivity's
# constructor calls FragmentActivity's, which calls kotlin.jvm.internal.Intrinsics.
# Every launch died with ClassNotFoundException on a real phone and the build never
# said a word. The stdlib is now a real dependency - see toolchain/setup.sh step 6c.)
#
# What is left below is the complete list of classes the dex references and does not
# define, 18 of them, in two families. They stay because the libraries that mention
# them are compile-time dependencies of Material/AppCompat, not because the paths are
# guessed to be dead: each referencing method is named below so it can be checked.
#
# 1) OEM-provided window APIs. androidx.window declares these compileOnly and resolves
#    them reflectively inside try/catch from SafeWindowLayoutComponentProvider and
#    SidecarCompat - they exist on some Samsung/ChromeOS devices and nowhere else.
#    Referencing methods: SafeWindowLayoutComponentProvider$windowLayoutComponent$2
#    .invoke(); SidecarCompat.validateExtensionInterface(); SidecarCompat$Companion
#    .getSidecarCompat().
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
#
# 2) kotlinx.coroutines, referenced from exactly two methods:
#      androidx.slidingpanelayout.widget.FoldingFeatureObserver
#          .registerLayoutStateChangeCallback(Activity)      <- foldable hinge support
#      androidx.lifecycle.SavedStateHandle.set(String, Object) <- ViewModel saved state
#    Neither can run here: no layout inflates a SlidingPaneLayout and no code names one,
#    and there is no ViewModel or SavedStateHandle anywhere in the sources. They survive
#    shrinking only because SlidingPaneLayout is a View and the AppCompat keep rule below
#    - which XML inflation by name requires - retains every View subclass.
#    androidx.core.os.BundleKt is the same story one step further out: it is used by
#    SavedStateHandle's coroutine path only.
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.core.os.BundleKt

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
