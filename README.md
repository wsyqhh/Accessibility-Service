# orb-a11y-http

Minimal Android AccessibilityService that exposes the current UI tree over a tiny HTTP API.

## Build

On a computer with Android SDK:

```bash
cd orb-a11y-http
./gradlew :app:assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## Install (ADB)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enable accessibility

Android Settings → Accessibility → **Accessibility Service** → Enable.

## Test on-device

```bash
adb shell curl -s http://127.0.0.1:7333/screen | head
adb shell curl -s -X POST 'http://127.0.0.1:7333/click?text=发送'
```

## Security note

This binds **127.0.0.1:7333** by default. If you change it to `0.0.0.0`, add an auth token.
