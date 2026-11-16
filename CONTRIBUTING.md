# Contributing

Thank you for your help. This file tells you how to set up the project and send a change.

## Set up the server

```bash
cd server
uv sync
uv run lapdrive serve --pair
```

## Set up the app

1. Install JDK 17 and the Android SDK.
2. Open `android/` in Android Studio, or use the command line:

   ```bash
   cd android
   ./gradlew assembleDebug
   ```

## Send a change

1. Fork the repository and make a branch from `main`.
2. Keep each pull request to one topic.
3. Make sure that the server starts and the app builds.
4. Open a pull request and describe what you changed and why.

## Security

Do not report security problems in public issues. Read [SECURITY.md](SECURITY.md).
