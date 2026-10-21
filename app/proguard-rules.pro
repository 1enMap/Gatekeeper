# Room and Compose ship their own consumer rules.

# Mode names are persisted (DataStore) and passed in notification intents; keep them stable.
-keepclassmembers enum app.gatekeeper.data.Mode { *; }
