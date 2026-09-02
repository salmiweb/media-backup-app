# Android Notification Media Backup Utility
Built with Kotlin, Jetpack Compose, Material 3, Scoped Storage, and NotificationListenerService.

## How to Run in Android Studio
1. Open Android Studio (Hedgehog / Iguana / Koala / Ladybug or newer).
2. Click **Open Project** and select this directory.
3. Sync Gradle and run the app on an Android device or emulator (Android 8.0+ / API 26 to Android 15 / API 35).
4. In the app, click **Enable** on the Notification Listener Service card to grant Notification Access.
5. Grant Media Permissions (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO).
6. Backed-up files are stored in `/Pictures/SavedMediaBackup/`.
