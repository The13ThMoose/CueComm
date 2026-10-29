# kotlinx.serialization (from the library's documentation)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class no.meltveit.cuecomm.**$$serializer { *; }
-keepclassmembers class no.meltveit.cuecomm.** { *** Companion; }
-keepclasseswithmembers class no.meltveit.cuecomm.** { kotlinx.serialization.KSerializer serializer(...); }
