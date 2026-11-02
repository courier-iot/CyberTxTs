# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.stellarite.apps.data.**$$serializer { *; }
-keepclassmembers class com.stellarite.apps.data.** { *** Companion; }
-keepclasseswithmembers class com.stellarite.apps.data.** { kotlinx.serialization.KSerializer serializer(...); }
