# MADDY BGMI STORE — Admin Panel Android App

## Quick Start (Android Studio)

1. **Open project**: File → Open → select this `MaddyBGMIStore` folder
2. **Sync Gradle**: Android Studio will prompt — click "Sync Now"
3. **If Gradle wrapper error**: Run `gradle wrapper --gradle-version=8.4` in terminal, then sync again
4. **Build APK**: Build → Build Bundle(s) / APK(s) → Build APK(s)
5. **Find APK**: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34 (install via SDK Manager if missing)
- Target device: Android 6.0+ (minSdk 26)

## App Architecture

### LockActivity (Entry Point)
- Launches first, full-screen lock screen
- PIN setup on first run (4–6 digits, SHA-256 + salt in SharedPreferences)
- Biometric (fingerprint) auto-prompt on subsequent launches
- 5-attempt lockout with 30-second timeout
- FLAG_SECURE on all windows (screenshot prevention)

### MainActivity (WebView)
- Loads: `https://www.maddybgmistore.in/admin`
- **Chrome UA fix**: Uses exact Chrome 124 UA string → prevents Google Error 403: disallowed_useragent
- **Firebase sessionStorage fix**: ALL auth URLs (accounts.google.com, firebaseapp.com, etc.) 
  stay inside the same WebView — never opened in Chrome Custom Tabs or external browser
- WebView settings: domStorage, database, JavaScript, third-party cookies, MIXED_CONTENT_COMPATIBILITY_MODE
- Pull-to-refresh, offline detection, download manager, file upload (gallery + camera)
- Back navigation through WebView history
- Toolbar: MBS logo (gold ring) + "MADDY BGMI STORE" + LIVE badge + overflow menu

## Critical Technical Decisions

### Why the exact Chrome UA?
```
Mozilla/5.0 (Linux; Android 14; CPH2609) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36
```
Google blocks sign-in for non-browser user agents. This exact string passes Google's allowlist.

### Why keep auth URLs inside WebView?
Firebase uses `sessionStorage` to maintain OAuth state. If the auth redirect opens in Chrome 
or Custom Tabs, it's a *different* session → sessionStorage is empty → Firebase throws:
`"Unable to process request due to missing initial state"`

Auth domains handled internally: `accounts.google.com`, `firebaseapp.com`, 
`oauth2.googleapis.com`, `securetoken.googleapis.com`, `identitytoolkit.googleapis.com`

### Why no adaptive icon XML?
Per your specification, real PNG files at each mipmap density are used instead.
This avoids Android adaptive icon shape issues on different launchers.

## File Structure
```
app/src/main/
├── java/in/maddybgmistore/admin/
│   ├── LockActivity.java       ← PIN + biometric lock screen
│   └── MainActivity.java       ← WebView + Firebase auth fixes
├── res/
│   ├── drawable/
│   │   ├── gradient_overlay.xml   ← Dark overlay on lock screen wallpaper
│   │   ├── mbs_logo.png           ← Logo for layouts
│   │   └── wallpaper_lock.jpg     ← MBSxWallpaper3 as lock background
│   ├── layout/
│   │   ├── activity_lock.xml      ← PIN + biometric UI
│   │   └── activity_main.xml      ← WebView + toolbar + offline screen
│   ├── mipmap-{density}/
│   │   ├── ic_launcher.png        ← From MBSxLogo.png (5 densities)
│   │   └── ic_launcher_round.png  ← Circular version (5 densities)
│   ├── menu/main_menu.xml
│   ├── values/
│   │   ├── colors.xml    ← Gold (#FFD700), green (#00E676), dark theme
│   │   ├── strings.xml
│   │   └── themes.xml    ← Dark MaterialComponents theme
│   └── xml/
│       ├── file_paths.xml              ← FileProvider paths
│       └── network_security_config.xml ← HTTPS enforcement
└── AndroidManifest.xml
```

## Signing for Release
To generate a signed APK:
1. Build → Generate Signed Bundle / APK
2. Create or select your keystore
3. Build → the signed APK goes to `app/release/`

## Troubleshooting

**"Missing class" errors**: Run File → Invalidate Caches / Restart

**Google Sign-In fails with 403**: Ensure the UA string in MainActivity has NOT been modified.
It must exactly match the CHROME_UA constant.

**"Unable to process request due to missing initial state"**: An auth URL was intercepted 
and opened externally. Check shouldOverrideUrlLoading() — all AUTH_DOMAINS must return false.

**Biometric not showing**: Device may not have enrolled fingerprints. 
Go to Settings → Security → Fingerprint and enroll one.

**Black screen on launch**: FLAG_SECURE is working. In-app screenshots are blocked by design.
