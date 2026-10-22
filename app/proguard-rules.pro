# Room and Compose ship their own consumer rules.

# Mode names are persisted (DataStore) and passed in notification intents; keep them stable.
-keepclassmembers enum app.gatekeeper.data.Mode { *; }

# Tor mode: tor-android's native code reads TorService's fields and methods by name.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
