# Privacy Policy for NullRoute

**Last Updated:** September 2026

NullRoute ("we", "our", or "the app") is developed by Stefan Gheorghe as a lightweight, privacy-first personal productivity and self-control tool for Android. 

We believe that your personal browsing habits, device usage, and focus routines should remain strictly private. **NullRoute operates 100% locally on your device. We do not collect, store, transmit, or sell any personal data, browsing history, or device identifiers.**

---

## 1. Information Collection & Processing

NullRoute does not require account creation, registration, or login. 

* **No Personal Data Collected:** We do not collect names, email addresses (except when you voluntarily contact support), phone numbers, or hardware IDs.
* **No Analytics or Trackers:** NullRoute contains zero third-party advertising SDKs, tracking pixels, or third-party behavioral analytics libraries.
* **No Remote Telemetry:** All query counts, latency stats, and diagnostic telemetry displayed in the app are calculated entirely in device memory and are never transmitted to any external server.

---

## 2. Use of Android Permissions & Services

NullRoute requests specific high-privilege Android permissions solely to perform its core website blocking and self-control functionality.

### A. Android VpnService (`BIND_VPN_SERVICE`)
* **Purpose:** NullRoute uses Android's `VpnService` to implement an on-device local DNS loopback resolver.
* **How It Works:** When activated, NullRoute captures DNS requests locally on the device to intercept domain names configured in your blocklist. If a domain matches your blocked list, the DNS query is answered with a loopback address (`0.0.0.0`), preventing the site from loading in any browser or app.
* **Data Security & Privacy:** 
  * NullRoute **does not** route your internet traffic through any external VPN servers or remote proxies.
  * Your traffic never leaves your device through our service.
  * We **do not** inspect, log, store, or transmit your browsing activity, website URLs, or network payloads.

### B. Android AccessibilityService (`BIND_ACCESSIBILITY_SERVICE`)
* **Purpose:** NullRoute optionally employs an `AccessibilityService` for its "Hardened / Focus Lock Mode".
* **How It Works:** To assist users struggling with compulsive browsing or digital distractions, this service detects when the user navigates to the system settings page with the intention of disabling or uninstalling NullRoute while a lock is active, redirecting them back to the home screen.
* **Data Security & Privacy:**
  * The Accessibility Service is **never** used to read personal messages, passwords, credit card numbers, or sensitive content on your screen.
  * No accessibility event data or screen text is stored, logged, or transmitted off your device.

---

## 3. In-App Purchases & Payments

NullRoute offers an optional one-time purchase ("NullRoute Pro") to unlock additional productivity features (such as unlimited domains, permanent locks, and distraction presets).

* All financial transactions and payment processing are handled exclusively by **Google Play In-App Billing** (`com.android.vending.BILLING`).
* We do not collect, process, or store your credit card numbers, bank details, or billing address.
* Purchase verification is conducted directly through the official Google Play Services library on your device.

---

## 4. Data Storage on Device

All configuration files—including your custom blocked domain lists, permanent lock flags, and local app preferences—are stored strictly in your device's private internal storage (`/data/data/com.nullroute/`). This data is automatically and completely deleted if you uninstall the application or clear app data via system settings.

---

## 5. Children's Privacy

NullRoute is intended for general audiences seeking focus and digital productivity. We do not knowingly collect or solicit personal information from children under the age of 13.

---

## 6. Changes to This Privacy Policy

We may update this Privacy Policy from time to time to reflect changes in legal requirements or application features. Any updates will be posted with a revised "Last Updated" date at the top of this document.

---

## 7. Contact Us

If you have any questions, feedback, or concerns regarding this Privacy Policy or NullRoute's security practices, please contact us at:

* **Developer:** Stefan Gheorghe
* **Email:** [stefangh.devapps@gmail.com](mailto:stefangh.devapps@gmail.com)
* **Project Repository:** [https://github.com/gheorghe-stefan/NullRoute](https://github.com/gheorghe-stefan/NullRoute)
