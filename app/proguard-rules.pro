# Keep this file intentionally small. Library-specific rules should be added only when required.
# WorkManager reflects into this generated Room database during startup.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
