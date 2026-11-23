# kotlinx.serialization, WorkManager, Glance and Compose ship their own rules.
# Keep our serializable models' names stable for stored JSON.
-keep,includedescriptorclasses class io.github.morningcommutetool.data.**$$serializer { *; }
-keepclassmembers class io.github.morningcommutetool.data.** {
    *** Companion;
}
