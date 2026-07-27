# kotlinx.serialization: keep serializers for our own @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.transdroid.** {
    *** Companion;
}
-keepclasseswithmembers class org.transdroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}
