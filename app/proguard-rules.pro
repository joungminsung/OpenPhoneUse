# Release builds are shrunk with R8 to keep the APK small. The app's own classes and osmdroid stay whole.

# Claude: the Anthropic SDK ships its own rules for Jackson and Kotlin reflection (META-INF/proguard in the jar).
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
