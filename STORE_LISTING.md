# NullRoute — Google Play Store Listing & Promotion Kit

This document contains everything you need to fill out your Google Play Store listing: descriptions, metadata, privacy policy link, feature graphic guidelines, and screenshot templates.

---

## 1. Store Metadata & Descriptions

### App Title (Max 30 characters)
```text
NullRoute: Website Blocker
```

### Short Description (Max 80 characters)
```text
Lightweight system-wide website & distraction blocker for focus and self-control.
```

### Full Description (Formatted for Google Play)
```text
Tired of losing hours to compulsive scrolling and digital distractions? 

NullRoute is a lightweight, system-wide website blocker designed specifically for deep focus, productivity, and unbreakable self-control. Unlike browser extensions that only work in a single browser, NullRoute runs at the Android OS level to block distracting domains across every web browser, social media viewer, and installed app.

🛡️ SYSTEM-WIDE BLOCKING (DNS LOOPBACK)
NullRoute uses a local, on-device loopback resolver via Android's VpnService. When a distracting website is queried, NullRoute resolves it locally to 0.0.0.0. No internet traffic ever leaves your device through external proxy servers.

🔒 HARDENED MODE (BEAT SELF-SABOTAGE)
Ever installed a blocker only to impulsively turn it off 10 minutes later? NullRoute’s hardened protection intercepts attempts to disable or uninstall the app during active focus sessions.

⚡ 1-CLICK FOCUS PRESETS
Instantly block entire distraction categories with a single tap:
• Social Media (Instagram, TikTok, Reddit, X / Twitter, Facebook)
• Video Feeds (YouTube, Netflix, Twitch)
• Doomscroll & News

📊 REAL-TIME ON-DEVICE TELEMETRY
View instant live statistics on blocked attempts, DNS query counts, cache hit ratios, and latency—all calculated 100% in local device memory.

☕ 100% PRIVATE & TRACKER-FREE
• Zero ads, zero tracking pixels, zero telemetry sent to remote servers.
• No account creation or login required.
• No monthly subscription fees.

------------------------------------------------------
FREE TIER vs. NULLROUTE PRO:
------------------------------------------------------
• Free Tier: Block up to 2 domains with full system-wide DNS blocking and live telemetry.
• NullRoute Pro (One-Time Lifetime Unlock):
  - Unlimited blocked domains
  - Permanent "Forever" hardened lock mode
  - 1-Click Distraction Presets
  - Direct support for independent, tracker-free development

Reclaim your attention and get into deep work with NullRoute.
```

---

## 2. Mandatory Google Play Links

* **Privacy Policy URL:**  
  `https://github.com/gheorghe-stefan/NullRoute/blob/main/PRIVACY_POLICY.md`  
  *(Ensure you push `PRIVACY_POLICY.md` to your repository's `main` branch).*
* **Support Email:**  
  `stefangh.devapps@gmail.com`
* **App Category:**  
  `Productivity` / `Tools`
* **Content Rating:**  
  `Everyone` (PEGI 3 / ESRB Everyone) — It contains no sensitive content, ads, or user-generated media.

---

## 3. Visual Assets Guidelines

Google Play requires specific graphic assets:

### A. App Icon
* **Dimensions:** 512 x 512 px
* **Format:** 32-bit PNG (with alpha)
* **File size limit:** 1024 KB
* *Note:* You already have high-resolution icon sources in the `icons/` folder (`icons/nullroute_icon_arranged.png`, `icons/nullroute_icon_round.png`).

### B. Feature Graphic (Displayed at the top of your store listing)
* **Dimensions:** 1024 x 500 px
* **Format:** JPEG or 24-bit PNG (no alpha)
* **Design Recommendation:** Deep slate background (`#0F172A`), centered NullRoute shield/lightning logo, and clean typography: *"NullRoute — System-Wide Focus & Website Blocker"*.

### C. Screenshots Strategy (Minimum 2, Recommended 4–5)
* **Dimensions:** Standard 9:16 phone resolution (e.g., **1080 x 2400 px** or **1440 x 3120 px**).
* **Suggested Screenshot Flow:**

| Slide | Headline Text | App Screen to Capture |
| :--- | :--- | :--- |
| **1** | **"System-Wide Website Blocking"** | Main Screen showing *Full Protection Enabled 🛡️* and active VPN/Accessibility toggles. |
| **2** | **"Block Distractions Everywhere"** | Blocked Domains list with custom domains added. |
| **3** | **"1-Click Focus Presets"** | Presets card highlighting *Socials, Video Feeds, and Doomscroll*. |
| **4** | **"Hardened Anti-Sabotage Lock"** | The *"Self-Sabotage Blocked"* alert or Permanent Lock dialog. |
| **5** | **"100% On-Device & Private"** | Expanded *Telemetry & Diagnostics* card showing zero remote data and live local query stats. |

---

## 4. Free Tools to Generate Sleek Mockup Screenshots

You don't need Photoshop to create professional screenshots with device frames and text headers:
* **Shots.so** (Free, browser-based, beautiful gradient backdrops & device frames)
* **Cleanmock.com** (Fast mobile mockups)
* **Figma** (Free templates for Google Play store screenshots)
