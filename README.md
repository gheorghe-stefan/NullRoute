 # NullRoute

  NullRoute is a system-wide website blocker for Android designed for personal productivity and self-control. Unlike browser extensions, it runs at the
OS level to filter and block specified domains across all web browsers and apps.

  ## Key Features

  - **System-Wide Blocking:** Uses Android's `VpnService` to capture and loopback DNS queries for blocked domains, preventing access across all
browsers and applications.
  - **Hardened Mode:** Employs an Android `AccessibilityService` to intercept attempts to disable the app, toggle its permissions, or uninstall it
through system settings.
  - **Permanent Lists:** Key blocked sites are compiled directly into the application code (or frozen upon activation) to prevent self-sabotaging
configuration changes.

  ## Tech Stack

  - **Language:** Kotlin
  - **UI Framework:** Jetpack Compose
  - **Build System:** Gradle (Kotlin DSL)
  - **Min SDK:** API 26 (Android 8.0)
  - **Target SDK:** API 34 (Android 14)

  ## Installation & Running

  This app is built without Android Studio using standard command-line tools.

  1. Ensure you have the Android SDK and JDK 17 installed.
  2. Build the debug APK:
     ```bash
     ./gradlew assembleDebug

3. Install the APK on your USB-connected device:
  ./gradlew installDebug


## Emergency Removal

To prevent impulsive disabling, NullRoute actively locks access to the Settings application. If you must uninstall the app (e.g., in an emergency or
for debugging), you must boot your device into Safe Mode to bypass the protection.

For full instructions, refer to the Emergency Removal Guide /REMOVAL_GUIDE.md.
