# FreeShare

FreeShare is an Android connection-sharing app built around Wi-Fi Direct and a lightweight HTTP/HTTPS proxy. It creates a private wireless network on the phone, accepts proxy connections on `192.168.49.1:8282`, and sends that traffic through the phone's active internet connection.

Its objective is to provide portable, no-root internet sharing without an application-level speed limit. The network name and password are configurable, connected devices are shown in the app, and built-in instructions explain proxy setup for macOS, Windows, iPhone/iPad, Android, and Linux.

The proxy supports regular HTTP requests and HTTPS `CONNECT` tunnels. It only listens on the Wi-Fi Direct interface and does not decrypt HTTPS traffic.

## Build

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Pushes to `main` also publish an installable APK through GitHub Releases.
