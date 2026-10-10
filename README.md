# ASTRA LoreMotion Headless Browser Bridge

This Android app runs a local bridge plus a Linux ARM64 Chrome Headless Shell inside the bundled Ubuntu/Proroot rootfs.

## Start

1. Open **ASTRA Browser Control**.
2. Tap **ЗАПУСТИТЬ ASTRA BROWSER**. The first launch downloads the official Chrome for Testing ARM64 archive, extracts it into the app's private Ubuntu rootfs, and installs Chrome's shared-library dependencies. This first setup can take several minutes. Leave the app open until it reports `ONLINE`.
3. The local bridge is at `http://127.0.0.1:18765`; the Chrome DevTools Protocol endpoint is `http://127.0.0.1:9222/json/version`.

## Termux checks

```sh
curl -sS http://127.0.0.1:18765/health
curl -sS http://127.0.0.1:18765/headless/status
curl -sS http://127.0.0.1:18765/headless/log
curl -sS -X GET 'http://127.0.0.1:18765/headless/open?url=https%3A%2F%2Floremotion.com%2Fgenerate%2F'
curl -sS http://127.0.0.1:9222/json/version
```

`/headless/log` exposes the full tail of the runtime log so startup failures can be diagnosed from Termux without relying on the Android status card, which can truncate long output.

## Persistent profile and sign-in

Chrome stores its profile inside the app's private Ubuntu rootfs at `/root/.astra/loremotion-profile`. The profile is reused on subsequent starts and is separate from Vivaldi; the app does not copy Vivaldi/Google cookies or store account passwords. Sign in to LoreMotion in this headless profile once through ASTRA's CDP-connected Browser Worker. The website session can persist between restarts as long as the app data is not cleared or the app is uninstalled. If the provider forces a new login or blocks headless sign-in, that must be completed through an approved interactive login flow; do not send passwords to ChatGPT.

## Existing ASTRA automation

The existing ASTRA Browser Worker can connect to CDP on port `9222`. Continue using the existing LoreMotion adapter to enter the prompt, choose 9:16, wait for generation, save the MP4, and send it to Telegram. This bridge only hosts the browser runtime and does not hard-code a Telegram bot token into the APK.

## Notes

- Chrome is downloaded over HTTPS from Google's official Chrome for Testing storage URL.
- Chrome's runtime libraries are installed inside the existing Ubuntu rootfs; Termux's package database is not used by the app. The installer checks that Ubuntu package indexes and package candidates are really present instead of trusting a successful `apt-get update` exit code alone. It tries the Ubuntu ports repository over HTTPS first (Ubuntu package signatures are still checked against the bundled archive keyring), then HTTP if HTTPS cannot produce usable indexes.
- The Android foreground-service notification remains visible while the background browser service is running, as required by Android. The browser window itself is headless and is not displayed on screen.
