# Keep kotlinx-serialization generated serializers
-keepclassmembers class com.chengyan.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.chengyan.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.chengyan.app.**$$serializer { *; }
