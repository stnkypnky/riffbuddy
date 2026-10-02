# Add app-specific ProGuard rules here.

# alphaSkia (alphaTab's renderer) is native code that finds its Java half by
# name: its JNI functions are bound to the literal names
# Java_alphaTab_alphaSkia_AlphaSkiaCanvas_fillRect and so on, and it reads a
# field called `handle` with GetFieldID. R8 renames all of that in a release
# build, and the first tab opened took the app down with
# ClassNotFoundException: alphaTab.alphaSkia.AlphaSkiaNative — a native abort,
# so no Kotlin stack trace and no catch that could help. Debug builds are not
# shrunk, which is why every device test before 2026-09-22 passed. The
# package is a dozen small classes; keeping all of it whole costs nothing.
-keep class alphaTab.alphaSkia.** { *; }
