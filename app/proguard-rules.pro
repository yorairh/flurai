# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# kotlinx.serialization: navigation routes and the jiten.moe responses are @Serializable.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.flurai.app.**$$serializer { *; }
-keepclassmembers class com.flurai.app.** { *** Companion; }

# Firestore maps documents onto our data classes by reflection.
-keepclassmembers class com.flurai.app.core.sync.** { *; }
