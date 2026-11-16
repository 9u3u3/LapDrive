# LapDrive

[![CI](https://github.com/9u3u3/LapDrive/actions/workflows/ci.yml/badge.svg)](https://github.com/9u3u3/LapDrive/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Python 3.11+](https://img.shields.io/badge/python-3.11%2B-blue)
![Android 10+](https://img.shields.io/badge/Android-10%2B-green)

LapDrive lets your Android phone access the files on your laptop. You can browse, preview, download and upload files.

- `server/`: Python HTTPS server for the laptop.
- `android/`: Kotlin + Jetpack Compose app for the phone.

## Set up the laptop

1. Install the `lapdrive` command:

   ```bash
   uv tool install ./server
   ```

2. Start the server with pairing on:

   ```bash
   lapdrive serve --pair
   ```

3. On the phone, open LapDrive and tap **Scan QR code**. Scan the QR code in the terminal.

After the first pairing, start the server without `--pair`:

```bash
lapdrive serve                      # share your home folder
lapdrive serve --root ~/Documents   # share one folder only
lapdrive serve --read-only          # no upload, rename or delete
```

Other commands:

```bash
lapdrive devices        # list paired phones
lapdrive revoke <id>    # block a phone now (works on a running server)
lapdrive reset-cert     # new certificate, all phones must pair again
```

The default port is `8443`. If your firewall blocks it, open it on your home network only. For access away from home, use Tailscale. The QR code includes your Tailscale address, and the app tries each address in turn.

## Build the app

```bash
cd android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## Security

| Risk | Protection |
|---|---|
| Someone on the same Wi-Fi reads or changes the traffic | HTTPS only (TLS 1.2 or 1.3). The app pins the SHA-256 fingerprint of the laptop certificate. The fingerprint comes from the QR code, so the first connection is also protected. |
| A different device connects to the laptop | Each request needs a 256-bit device token. The laptop stores only the SHA-256 hash of each token. |
| Someone guesses the pairing code | Pairing works only with `--pair`. The 10-character code works one time, expires after 5 minutes, and stops after 5 wrong attempts. |
| Someone guesses tokens | After 20 failed attempts in 10 minutes, the server blocks that IP address for 15 minutes. |
| A request reads files outside the shared folder | The server resolves every path and symlink and rejects paths outside the root. It hides dot files by default. |
| Someone takes the phone | The app locks with your fingerprint or screen lock after 30 seconds in the background. |
| Another app or a backup reads the token | The token is encrypted with AES-256-GCM. The key is in the Android Keystore. App backup is off. |
| Screenshots or the recent-apps view show files | `FLAG_SECURE` blocks screenshots, screen recording and the recent-apps preview. |
| Previews stay on the phone | Thumbnails and images stay in memory only. Video streams with no disk cache. PDF and "Open with" use a private temporary folder. The app deletes it when you close the preview and when the app starts. |
| A delete by mistake | Delete moves the file to the laptop trash. |

Limits:

- Files that you download go to `Downloads/LapDrive`. Other apps with storage access can read them there.
- "Open with" gives one other app read access to a temporary copy.
- On the laptop, thumbnails go to `~/.cache/lapdrive` (mode 0700).
- The private key is in `~/.config/lapdrive` (mode 0600). Any program that runs as your user can read it.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems as [SECURITY.md](SECURITY.md) tells you.

## License

LapDrive uses the [MIT License](LICENSE).
