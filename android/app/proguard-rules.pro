# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.syncliko.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.syncliko.**$$serializer { *; }

# ML Kit code scanner (Google Play services). Its internals construct components reflectively;
# R8 full mode merged/stripped them and the scanner crashed with an NPE in release builds.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
