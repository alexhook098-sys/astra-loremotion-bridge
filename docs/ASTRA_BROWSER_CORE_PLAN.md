# ASTRA Browser Core — migration plan

Base: Build #437
Base commit: 922be3c3ef4c2d4dda7ca76b6985235b87675550
Working branch: feature/astra-browser-core-in-chat

## Goal

Make Perchance and Google Flow usable as ASTRA-managed browser sessions without exposing separate browser/task windows to the user. Keep the existing WebView engine, authenticated site state, generation automation, and result-delivery paths until each migration step is verified.

## Verified starting state

- ChatActivity launches PerchanceBrowserActivity and FlowWebViewActivity with startActivity().
- PerchanceBrowserActivity and FlowWebViewActivity each create and own a WebView.
- BrowserBridgeService is a foreground service, but currently starts LocalBridgeServer; it does not own or coordinate those WebViews.
- WebViewBrowserActivity is another separate Activity-based WebView host.
- Manifest declares separate taskAffinity values and excludeFromRecents="false" for browser activities.
- Perchance and Flow deliver completed media back to ChatActivity using persisted pending-result queues / callbacks.
- app/build.gradle already includes androidx.webkit:webkit:1.18.0.

## Non-negotiable compatibility rules

1. Do not replace the WebView engine with remote APIs or Headless Chrome as part of this migration.
2. Do not change Perchance/Flow prompt injection, DOM selectors, download/capture bridges, or file delivery while introducing the coordinator.
3. Preserve the existing WebView cookie/profile state. Do not clear cookies, WebStorage, cache, or app data.
4. Do not remove existing Activities until an equivalent path is tested on-device.
5. Do not change ZSky, One Click Motion, Story Motion, RIFE, FFmpeg, or their build packaging in this workstream.
6. Never claim background execution is reliable merely because an Activity is hidden or excluded from Recents. Verify real site generation and downloads with the screen locked and ASTRA foregrounded.

## Proposed architecture

- BrowserSessionManager: app-level coordinator for provider sessions and commands.
- BrowserProviderAdapter: small provider contract for opening/reusing a session, submitting a task, reporting state, and delivering output.
- Perchance adapter and Flow adapter: initially wrap existing Activity/WebView implementations without changing their internals.
- BrowserBridgeService: remains responsible for the local bridge and notification; later receives explicit session-state updates from the manager.
- ChatActivity: sends semantic commands to the manager and displays task state/results; it must not know which Activity class implements a provider.

Suggested task states: IDLE, OPENING_SESSION, READY, SUBMITTING, RUNNING, OUTPUT_DETECTED, DOWNLOADING, VERIFYING, DELIVERED, FAILED.

## Migration sequence

### Phase 0 — baseline and safety
- Keep Build #437 as the immutable baseline.
- Work only on feature/astra-browser-core-in-chat.
- Record current file hashes and successful baseline behaviors before functional edits.

### Phase 1 — coordinator without behavior changes
- Add BrowserSessionManager and provider/session identifiers.
- Route ChatActivity's Perchance and Flow launch requests through the manager.
- Initially preserve the current Activity launches exactly as the adapter implementation.
- Verify manual open, prompt submission, image/video capture, pending-result delivery, and re-entry into an existing session.

### Phase 2 — task/window behavior
- Investigate removing browser sessions from Android Recents and preventing visible foreground flashes.
- Test manifest/task changes independently from provider automation.
- Do not assume excludeFromRecents alone solves foreground flashing or hidden-WebView throttling.

### Phase 3 — shared in-app browser host
- Move one provider at a time behind a single ASTRA-owned host/session lifecycle.
- Keep each provider's existing capture and automation code intact during the move.
- Verify cookies, uploads, downloads, popup handling, redirects, file chooser, and JS execution.
- Only migrate the second provider after the first passes the full regression matrix.

### Phase 4 — background reliability
- Test generation with ASTRA visible, screen off, device locked, and after returning to chat.
- Use real completion signals and verified output files; do not rely only on JS timers or cosmetic progress indicators.
- Add notification progress only when driven by actual session state.

## Regression matrix

- Perchance: manual session open, existing login, prompt automation, generated image capture, image delivery into chat.
- Flow: manual session open, existing login, prompt automation, render completion, MP4 download/capture, video delivery into chat.
- Shared browser: switching providers, reopening a session, cookies, upload chooser, redirects, popup behavior.
- Existing features: ZSky, One Click Motion, ASTRA Story Motion, RIFE, FFmpeg, and GIF Studio remain present and unchanged.
- Android task behavior: no unexpected Recents cards; no visible browser flash; chat remains interactive.
- Failure behavior: network loss, process recreation, duplicate result delivery, failed download, and retry.

## First implementation boundary

The first code change should add only the coordinator/provider contract and route ChatActivity requests through it while retaining the current Activity adapters. No manifest changes and no WebView relocation in the same commit. This keeps the first diff small and reversible.
