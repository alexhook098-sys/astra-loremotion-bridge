# ASTRA LoreMotion Bridge

Android bridge between ASTRA/Termux and an already-authorized Vivaldi session.

Architecture:

ASTRA (Termux) -> 127.0.0.1:8765 -> AccessibilityService -> Vivaldi -> LoreMotion

The bridge does not copy Google/LoreMotion cookies, passwords, or tokens.

Endpoints:
- GET /health
- GET /dump
- GET /open?url=...
- GET /click?text=...
- GET /type?field=...&value=...

Enable the Accessibility Service in Android settings before using /dump, /click, or /type.
