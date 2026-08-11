-keepattributes Signature
-keepattributes *Annotation*

# Preserve Gson generic type metadata and model names without blocking app-wide shrinking.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
