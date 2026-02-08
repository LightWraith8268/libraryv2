# Keep Room entities
-keep class com.lightwraith8268.libraryiq.data.local.entity.** { *; }

# Keep Gson serialization models (Google Books, Open Library, Hardcover)
-keep class com.lightwraith8268.libraryiq.data.remote.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Gson annotations
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
