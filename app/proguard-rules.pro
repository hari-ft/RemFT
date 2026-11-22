-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.ftapps.remft.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.ftapps.remft.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.ftapps.remft.data.db.entity.** { *; }
