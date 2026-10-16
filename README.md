# ASTRA LoreMotion Headless Browser Bridge

This Android app runs a local bridge plus a Linux ARM64 Chrome Headless Shell inside the bundled Ubuntu/Proroot rootfs.

## Start the bridge

1. Open **ASTRA Browser Control**.
2. Tap **ЗАПУСТИТЬ ASTRA BRIDGE**. This starts the local HTTP bridge at `http://127.0.0.1:18765`. It does not open WebView automatically.
3. To sign in interactively, tap **ПОКАЗАТЬ WEBVIEW**. The WebView session uses Android's persistent cookies and DOM storage.
4. The separate headless Chrome runtime is optional. Start it explicitly through `/headless/start`; its CDP endpoint is `http://127.0.0.1:9222/json/version`. First-time setup can take several minutes.

## Termux checks

```sh
curl -sS http://127.0.0.1:18765/health
curl -sS http://127.0.0.1:18765/headless/status
curl -sS http://127.0.0.1:18765/headless/log
curl -sS -X GET 'http://127.0.0.1:18765/headless/open?url=https%3A%2F%2Floremotion.com%2Fgenerate%2F'
curl -sS http://127.0.0.1:9222/json/version
```

`/headless/log` exposes the full tail of the runtime log so startup failures can be diagnosed from Termux without relying on the Android status card, which can truncate long output.

## On-demand WebView lifecycle

ASTRA should start the WebView only when a task needs LoreMotion. The bridge service can remain online while the WebView Activity is stopped.

```sh
# Open the generator in the background (creates the WebView if needed).
curl -sS 'http://127.0.0.1:18765/webview/start?url=https%3A%2F%2Floremotion.com%2Fgenerate%2F'

# After the MP4 has been downloaded and verified, close the WebView.
curl -sS 'http://127.0.0.1:18765/webview/close'

# Alternative: reload the generator page after a saved task instead of closing.
curl -sS 'http://127.0.0.1:18765/webview/refresh?url=https%3A%2F%2Floremotion.com%2Fgenerate%2F'

# Verify that close completed.
curl -sS 'http://127.0.0.1:18765/webview/status'
```

`/webview/close` closes only the WebView Activity, not the HTTP bridge foreground service. It flushes cookies before destruction. A later `/webview/start` creates a fresh WebView and reloads LoreMotion while retaining persisted site data. Always call close/refresh only after ASTRA has fully saved and verified the MP4; reloading during generation would cancel the task.

## Rootfs DNS and package setup

Before Ubuntu's package manager runs, ASTRA replaces a dangling rootfs `/etc/resolv.conf` with DNS servers read from Android's active network, followed by public fallback resolvers. The runtime confirms that `ports.ubuntu.com` resolves before downloading package indexes. Ubuntu APT keeps its normal repository signature validation; HTTPS verification is not disabled.

If setup fails, retrieve the complete log from Termux with `curl -sS http://127.0.0.1:18765/headless/log`. The downloaded Chrome archive and extracted binary remain in app-private storage, so retrying setup does not download Chrome again when its extracted executable is intact.

## Persistent profile and sign-in

Chrome stores its profile inside the app's private Ubuntu rootfs at `/root/.astra/loremotion-profile`. The profile is reused on subsequent starts and is separate from Vivaldi; the app does not copy Vivaldi/Google cookies or store account passwords. Sign in to LoreMotion in this headless profile once through ASTRA's CDP-connected Browser Worker. The website session can persist between restarts as long as the app data is not cleared or the app is uninstalled. If the provider forces a new login or blocks headless sign-in, that must be completed through an approved interactive login flow; do not send passwords to ChatGPT.

## Existing ASTRA automation

The existing ASTRA Browser Worker can connect to CDP on port `9222`. Continue using the existing LoreMotion adapter to enter the prompt, choose 9:16, wait for generation, save the MP4, and send it to Telegram. This bridge only hosts the browser runtime and does not hard-code a Telegram bot token into the APK.

## Notes

- Chrome is downloaded over HTTPS from Google's official Chrome for Testing storage URL.
- Chrome's runtime libraries are installed inside the existing Ubuntu rootfs; Termux's package database is not used by the app. The installer checks that Ubuntu package indexes and package candidates are really present instead of trusting a successful `apt-get update` exit code alone. It tries the Ubuntu ports repository over HTTPS first (Ubuntu package signatures are still checked against the bundled archive keyring), then HTTP if HTTPS cannot produce usable indexes.
- The Android foreground-service notification remains visible while the background browser service is running, as required by Android. The browser window itself is headless and is not displayed on screen.
