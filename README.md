# WatchBrowser — tiny browser for Galaxy Watch 4 Classic

Wear OS on the Galaxy Watch 4 has **no Android System WebView**, so this app brings its own engines:

- **Text mode (default)** – the app downloads the page itself and shows the text with clickable links.
  No WebView, no JavaScript, no images: a few MB of RAM and almost no battery.
- **Full mode** – Mozilla's GeckoView (Firefox engine) for pages that need JavaScript. It is only
  started when you switch to Full, with ad/tracker blocking on, and is shut down when you switch back.

## Using it
- Tap the small **☰** button at the bottom for the menu; tap the dark area to close it.
- **Full / Text** switches engines for the current page.
- **JS** turns JavaScript on/off in Full mode. **Clear** wipes history, cookies and cache.
- **Rotate the bezel** to scroll. Swipe right / back button goes back.
- Home and search use DuckDuckGo's lightweight HTML page.

## Build the APK (no install needed: GitHub Actions)
1. Create a new GitHub repo and upload this whole folder (including `.github`).
2. Open the **Actions** tab → "Build APK" runs automatically (or press *Run workflow*).
3. When it finishes, download **WatchBrowser-apk** from the run page and unzip it. It contains
   `app-armeabi-v7a-release.apk` (32-bit) and `app-arm64-v8a-release.apk` (64-bit).
   Check which your watch needs with `adb shell getprop ro.product.cpu.abi`.

Or with Android Studio: *File → Open* this folder, then *Build → Build APK(s)*.

## Install on the watch
1. Watch: **Settings → About watch → Software → tap "Software version" 5×** to enable Developer options.
2. **Settings → Developer options** → turn on **ADB debugging** and **Wireless debugging**.
3. In Wireless debugging, tap **Pair new device** — note the IP:port and pairing code.
4. On your computer (with [platform-tools](https://developer.android.com/tools/releases/platform-tools)):
   ```
   adb pair <ip>:<pair-port>        # enter the code
   adb connect <ip>:<port>          # port shown on the main Wireless debugging screen
   adb install app-armeabi-v7a-release.apk   # or the arm64 one
   ```
   (Watch and computer must be on the same Wi-Fi.)

## Notes
- Change the home page/search engine at the top of `MainActivity.java`.
