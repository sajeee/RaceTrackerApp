Race Tracker - Android Studio project (Java)

Features:

- Foreground service tracking (NETWORK + GPS) every 5 seconds
- Posts JSON to: https://web-production-58a8f.up.railway.app/tracking/api/tracking/1/post_location/
- Prompts user for Runner ID once and reuses it
- UI: Start / Stop / Status

How to use:

1. Open Android Studio -> Open an existing project -> select this folder.
2. Let Gradle sync.
3. Build -> Build APK(s) -> app-debug.apk
4. Install on device and grant location permissions. Tap 'Start Tracking'.

Notes:

- For production: add auth tokens, error handling, and network retry/backoff.
