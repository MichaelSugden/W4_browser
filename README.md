# WatchBrowser — tiny browser for Galaxy Watch 4 Classic

Plain Java + the system WebView. No AndroidX, no Kotlin, no libraries → APK around 30 KB,
and it adds only a few MB of RAM on top of the WebView itself.

## Features
- **Bezel scrolling** – rotate the bezel to scroll pages.
- **Round-screen fit** – pads pages so text isn't clipped by the circle (toggle "Fit").
- **Images off by default** – the biggest RAM/data/battery saver (toggle "Img").
- **JavaScript toggle** – turn off for static sites to cut CPU use (toggle "JS").
- **Reader mode** – "Read" strips a page to text on a black background (OLED-friendly).
- **Lite search** – DuckDuckGo HTML version as home page.
- Pauses all page timers when the screen goes off; drops cache when memory runs low.
- Back button / swipe-back goes back in history.

Tap the small ☰ button at the bottom for the menu; tap the dark area to close it.

## Build the APK (no install needed: GitHub Actions)
1. Create a new GitHub repo and upload this whole folder (including `.github`).
2. Open the **Actions** tab → "Build APK" runs automatically (or press *Run workflow*).
3. When it finishes, download **WatchBrowser-apk** from the run page and unzip it.

Or with Android Studio: *File → Open* this folder, then *Build → Build APK(s)*.

## Install on the watch
1. Watch: **Settings → About watch → Software → tap "Software version" 5×** to enable Developer options.
2. **Settings → Developer options** → turn on **ADB debugging** and **Wireless debugging**.
3. In Wireless debugging, tap **Pair new device** — note the IP:port and pairing code.
4. On your computer (with [platform-tools](https://developer.android.com/tools/releases/platform-tools)):
   ```
   adb pair <ip>:<pair-port>        # enter the code
   adb connect <ip>:<port>          # port shown on the main Wireless debugging screen
   adb install app-release.apk
   ```
   (Watch and computer must be on the same Wi-Fi.)

## Notes
- Needs a system WebView on the watch. If it's missing, the app shows a message instead of crashing.
- Change the home page/search engine at the top of `MainActivity.java`.
