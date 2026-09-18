-keep class com.owncloud.android.lib.** { *; }
-dontwarn org.apache.**

# Optional annotation and logging binding referenced by the Nextcloud library.
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn org.slf4j.impl.StaticLoggerBinder
