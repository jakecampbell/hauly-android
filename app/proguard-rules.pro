# Retrofit + kotlinx.serialization
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jakecampbell.hauly.**$$serializer { *; }
-keepclassmembers class com.jakecampbell.hauly.** { *** Companion; }
-keepclasseswithmembers class com.jakecampbell.hauly.** { kotlinx.serialization.KSerializer serializer(...); }
