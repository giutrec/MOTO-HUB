# Extra anti-decompilation hardening for the closed-source Pro (branded "Advanced")
# release build only — layered on top of the shared proguard-rules.pro. None of this
# runs on coreRelease; Core's source is public so there is nothing to protect there.

# More aggressive R8 optimization/renaming than the shared defaults allow: lets R8
# merge classes with clashing member signatures and reuse the same short name for
# unrelated methods/fields across different classes, since real overload resolution
# doesn't need to hold once renamed.
-allowaccessmodification
-overloadaggressively

# Reuse ProGuard/R8's own advice for defeating decompilers: naming obfuscated
# classes/fields/methods after Java/Kotlin reserved words is legal at the JVM
# bytecode level but not valid Java source, which breaks or badly confuses common
# decompilers (jadx, procyon, cfr) more than plain a/b/c-style short names would.
-obfuscationdictionary proguard-dictionary-pro.txt
-classobfuscationdictionary proguard-dictionary-pro.txt
-packageobfuscationdictionary proguard-dictionary-pro.txt

# Adapt string literals that reference our own (now-renamed) class names, e.g. any
# Class.forName("io.motohub.android....") call sites.
-adaptclassstrings io.motohub.android.**

# NOTE: an -assumenosideeffects rule stripping android.util.Log calls used to live here (removes
# log strings + adb logcat visibility from the shipped binary). Pulled out during the beta while
# we're actively chasing real bugs on hardware — it was hiding exactly the ProjectionEventLog
# output needed to diagnose them. Re-add once Advanced is stable and closer to a real release.
