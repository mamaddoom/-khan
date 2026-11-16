-keep class com.mozkhan.shop.data.** { *; }
-keepclassmembers class com.mozkhan.shop.sync.** { *; }
-dontwarn com.google.errorprone.annotations.**

# MediaPipe (image embedder) is loaded through JNI and protobuf reflection: never strip or rename it
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
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
