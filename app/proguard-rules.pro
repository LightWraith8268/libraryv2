# Keep Room entities
-keep class com.inknironapps.libraryiq.data.local.entity.** { *; }

# Keep API models (Google Books, Open Library, Hardcover, Amazon)
-keep class com.inknironapps.libraryiq.data.remote.** { *; }

# Keep Hilt framework classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class dagger.hilt.android.internal.** { *; }

# Keep ALL ViewModels and their constructors — prevents R8 renaming that breaks
# Hilt's string-key based ViewModel factory lookup in hiltViewModel()
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepnames class * extends androidx.lifecycle.ViewModel

# Keep ALL Dagger @Module classes (including Hilt-generated ones) — in R8 full mode
# (default in AGP 8+), R8 can strip @Provides/@Binds methods from generated modules
# like XxxViewModel_HiltModules$KeyModule, breaking the multibinding map that
# hiltViewModel() relies on to find ViewModels at runtime.
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Keep Hilt-generated ViewModel modules and their inner classes explicitly.
# The $* pattern ensures inner classes (KeyModule, BindsModule) are kept even
# if '*' alone doesn't match '$' in this R8 version.
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_Factory { *; }
-keep class **_GeneratedInjector { *; }
-keep class com.inknironapps.libraryiq.Hilt_* { *; }

# Keep Hilt component interfaces
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { *; }

# Keep Hilt ViewModel factory and map infrastructure
-keep class dagger.hilt.android.internal.lifecycle.** { *; }
-keep class androidx.hilt.** { *; }

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

# --- Diagnostic: preserve class/method names for crash-prone libraries ---
# Prevents R8 from obfuscating these packages so stack traces are human-readable.
# Does NOT affect shrinking (unused code is still removed) — only naming.
-keepnames class androidx.navigation.** { *; }
-keepnames class androidx.lifecycle.** { *; }
-keepnames class androidx.hilt.** { *; }
-keepnames class androidx.compose.foundation.** { *; }
