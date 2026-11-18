-keep class com.mozkhan.shop.data.** { *; }
-keepclassmembers class com.mozkhan.shop.sync.** { *; }
-dontwarn com.google.errorprone.annotations.**

# MediaPipe (image embedder) is loaded through JNI and protobuf reflection: never strip or rename it
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# MediaPipe's Graph logs through Flogger. Flogger finds its backend BY CLASS NAME
# (Class.forName("com.google.common.flogger.backend.system.DefaultPlatform") and friends). When R8 renames those classes
# the lookup fails with "No logging platforms found", the static initialiser of com.google.mediapipe.framework.Graph throws,
# and every later use of Graph fails with NoClassDefFoundError: com.google.mediapipe.framework.Graph (release builds only).
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
-dontwarn javax.annotation.**
-dontwarn autovalue.shaded.**
# Credential Manager (Google sign-in) finds its Play Services provider by reflection: without this, release builds fail with
# "getCredentialAsync no provider dependencies found"
-keep class androidx.credentials.** { *; }
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
# Vosk speech recognition talks to its native library through JNA, which finds classes, fields and callbacks by name
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
