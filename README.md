# NullRoute 🛡️

**NullRoute** is a lightweight, distraction-free, system-wide website blocker for Android built for digital minimalism and deep focus.

Unlike ordinary browser extensions, NullRoute operates at the Android OS network level using a local dummy `VpnService` to sinkhole DNS queries. Distracting sites are blocked instantly across **all browsers and apps** with zero battery drain and zero remote servers.

---

## 🔒 100% Open Source & Privacy-First

> **Auditable Privacy:** NullRoute is 100% open source so you can inspect and verify the code yourself. It runs completely offline on your device, collects zero telemetry, logs no traffic, and requires no account.

### ☕ Support Solo Development
If you find NullRoute helpful for your productivity and self-control, please consider supporting solo development by purchasing **NullRoute Pro** on the Google Play Store (a one-time payment of €1.49 / $1.49):

👉 **[Get NullRoute on Google Play](https://play.google.com/store/apps/details?id=com.nullroute)** *(Coming soon / In review)*

---

## ✨ Features

- **Local DNS Sinkhole:** Blocks domains instantly by returning loopback responses (`127.0.0.1` / `::1`) locally on the device.
- **True Zero-Log & Battery Friendly:** No external VPN tunnel, no remote servers, no battery-draining background sync.
- **Removable vs. Permanent Mode:**
  - *Removable:* Test or manage domains with regular trash-can deletion.
  - *Permanent (Forever):* Lock distracting domains irreversibly to beat impulsive habits.
- **1-Click Presets:** Instantly block curated lists for Social Media (Instagram, TikTok, Twitter/X, Facebook) and Video Feeds (YouTube, Netflix, Twitch).
- **Automated URL Sanitization:** Paste raw URLs (e.g. `https://www.reddit.com/r/popular`) and NullRoute automatically strips protocols and paths to block the clean root domain.
- **Distraction Shield:** Blurred domain previews by default with tap-to-reveal to prevent browsing triggers inside the app.

---

## 📊 Free vs. Pro

| Feature | Free Tier | Pro Tier (One-Time €1.49) |
| :--- | :---: | :---: |
| **Max Blocked Domains** | 2 domains | **Unlimited (∞)** |
| **Removable Domains** | ✅ | ✅ |
| **Permanent (Forever) Locks** | ❌ | ✅ |
| **1-Click Curated Presets** | ❌ | ✅ |
| **Local DNS Privacy** | ✅ | ✅ |
| **Zero Ads / Zero Trackers** | ✅ | ✅ |

---

## 🛠️ Tech Stack

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose + Material 3
- **Architecture:** MVVM + StateFlow + Kotlin Coroutines
- **Networking/DNS:** Android `VpnService` (raw UDP DNS packet parser & synthesizer)
- **Monetization:** Google Play Billing Client 6.2.1
- **Target SDK:** API 34 (Android 14) / **Min SDK:** API 26 (Android 8.0)

---

## 🚀 Building From Source

1. Clone this repository:
   ```bash
   git clone https://github.com/gheorghe-stefan/NullRoute.git
   cd NullRoute
   ```
2. Build the debug APK using Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install to your connected USB/ADB device:
   ```bash
   adb install -r -d app/build/outputs/apk/debug/NullRoute-*.apk
   ```

---

## 🆘 Emergency Unblock / Safe Mode

If you permanently lock a domain or lock yourself out, you can boot your device into **Android Safe Mode** to disable non-system VPNs and services.

See the detailed instructions in [REMOVAL_GUIDE.md](REMOVAL_GUIDE.md).

---

## 📄 License

Distributed under the [MIT License](LICENSE).
