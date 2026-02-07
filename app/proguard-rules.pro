# Keep Room entities
-keep class com.booklib.app.data.local.entity.** { *; }

# Keep Gson serialization models
-keep class com.booklib.app.data.remote.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
