# Rules for the R8 release build (toolchain/build.sh --release) that only a human
# can supply. Nothing here may be a blanket -dontwarn: a "-dontwarn kotlin.**" in
# this file once hid the fact that the APK called kotlin.jvm.internal.Intrinsics
# without shipping it, and the build stayed green while every launch crashed.
# Missing classes are errors now; the toolchain vendors what AndroidX needs
# (setup.sh step 8b) and verify_apk.py proves the finished APK is self-contained.
#
# Manifest components need no entry: lib.sh generates keep rules from
# AndroidManifest.xml at release-dex time (toolchain/manifest_keep.py), the way
# AGP does, so a newly declared activity cannot be forgotten.
-keep class com.example.axsample.MainActivity { *; }
-keep class com.example.axsample.MainActivity$* { *; }

# kotlinx.coroutines: androidx.lifecycle's SavedStateHandle exposes getStateFlow()
# and friends for coroutine users, and the class that implements them references
# kotlinx.coroutines.flow.MutableStateFlow from kotlinx-coroutines-core, which is
# not vendored (this app has no coroutines). This is not a blanket -dontwarn: it
# is the exact family R8 names, with the method that names it, and the same
# reference is declared in packaging-allowlist.txt so the debug build has to
# account for it too. R8's report:
#   Missing class kotlinx.coroutines.flow.MutableStateFlow (referenced from:
#   void androidx.lifecycle.SavedStateHandle.set(java.lang.String, java.lang.Object))
-dontwarn kotlinx.coroutines.**
