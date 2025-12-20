# Add project specific ProGuard rules here.

# Suppress warnings for XML parsing
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.** { *; }
-dontwarn android.content.res.XmlResourceParser

# Keep Room annotations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep SQLCipher
-keep class net.zetetic.** { *; }
-keep class net.zetetic.database.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep model classes
-keep class com.nextcloud.sync.models.** { *; }
