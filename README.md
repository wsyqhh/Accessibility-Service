# Accessibility Service (HTTP)

Minimal Android **AccessibilityService** that exposes the current UI tree over a tiny local HTTP API, and (optionally) can inject input.

Repo: https://github.com/wsyqhh/Accessibility-Service

## What you get

- **Read UI tree**: `GET /screen`
- **Click by text/desc**: `POST /click?text=...`
- **Input injection**:
  - `POST /tap?x=&y=`
  - `POST /swipe?x1=&y1=&x2=&y2=&dur=`
  - `POST /key?name=home|back|enter|menu`

### Root vs non-root behavior

- **v0.2.0 ~ v0.2.2**
  - `/screen` works with accessibility enabled (no root required)
  - `/tap` `/swipe` `/key` require **root** (`su -c input ...`)

- **v0.3.0+**
  - `/screen` works with accessibility enabled (no root required)
  - `/tap` `/swipe`
    - if root available: `su -c input ...`
    - otherwise: fallback to Accessibility **dispatchGesture** (non-root touch injection)
  - `/key`
    - if root available: `su -c input keyevent ...`
    - otherwise: best-effort `performGlobalAction()` for home/back (menu is limited)

## Build

On a computer with Android SDK:

```bash
./gradlew :app:assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## Install (ADB)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you see signature mismatch on upgrade:

```bash
adb uninstall com.orb.a11y
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enable accessibility

Android Settings → Accessibility → **Accessibility Service** → Enable.

## Test on-device

The service binds to **127.0.0.1:7333** (device-local).

```bash
adb shell curl -s http://127.0.0.1:7333/screen | head
adb shell curl -s -X POST 'http://127.0.0.1:7333/click?text=发送'
adb shell curl -s -X POST 'http://127.0.0.1:7333/tap?x=540&y=980'
adb shell curl -s -X POST 'http://127.0.0.1:7333/swipe?x1=540&y1=2000&x2=540&y2=800&dur=300'
adb shell curl -s -X POST 'http://127.0.0.1:7333/key?name=home'
```

## Security note

This binds **127.0.0.1:7333** by default.

If you change it to `0.0.0.0`, add authentication (token) and consider TLS; otherwise anyone on the LAN could control the device.
