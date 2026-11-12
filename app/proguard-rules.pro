# Release builds are shrunk with R8 to keep the APK small. The libraries below ship their own rules too;
# these keep the reflection-heavy parts whole so a missed rule shows up as a bigger APK, not a crash.

# Claude: the Anthropic SDK builds its requests and responses with Jackson and Kotlin reflection.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.anthropic.**

# Our own @Serializable classes and anything the accessibility/MCP layers look up by name.
-keep class com.phoneuse.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Map tiles.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# Compile-time only references in the SDK's dependencies (OpenTelemetry, the JSON schema generator).
-dontwarn com.google.auto.value.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
