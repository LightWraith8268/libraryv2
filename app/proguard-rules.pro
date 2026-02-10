# Keep Room entities
-keep class com.inknironapps.libraryiq.data.local.entity.** { *; }

# Keep API models (Google Books, Open Library, Hardcover, Amazon)
-keep class com.inknironapps.libraryiq.data.remote.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Hilt ViewModels — R8 renames ViewModel classes, breaking hiltViewModel() lookup
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel { *; }

# Keep Hilt entry points and generated components
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class com.inknironapps.libraryiq.** extends androidx.lifecycle.ViewModel { *; }

# --- Retrofit ---
# Retrofit does reflection on generic parameters. InnerClasses is required to use Signature and
# EnclosingMethod is required to use InnerClasses.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Retrofit does reflection on method and parameter annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Keep annotation default values (e.g., retrofit2.http.Field.encoded).
-keepattributes AnnotationDefault

# Retain service method parameters when optimizing.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# With R8 full mode, it sees no subtypes of Retrofit interfaces since they are created with a Proxy
# and removes all methods. Explicitly keep the interface methods.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# R8 full mode strips generic signatures from return types if not kept.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowobfuscation,allowshrinking class <3>

# Keep Retrofit Response generic signature
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# --- Gson ---
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retain generic signatures of TypeToken and its subclasses so Gson can
# resolve the type at runtime (critical fix for ParameterizedType cast error)
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Prevent R8 from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# --- Kotlin coroutines ---
-dontwarn kotlin.Unit
-dontwarn kotlinx.coroutines.**
-keep class kotlin.coroutines.Continuation

# --- General ---
-keepattributes *Annotation*
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
