# break — Play anywhere.

A low-latency Android piano with real sampled instruments, live beats, a metronome and a smart mix engine. It updates itself from GitHub Releases.

## Build locally
Use JDK 17 (the JDK 21 on this machine fails to start `javac` inside Gradle).
```
JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot" ./gradlew assembleDebug
```
The APK is written to `app/build/outputs/apk/debug/`. The debug build installs as **Break (app.breakpiano.dev)** next to the release build.

## Releases & auto-update
1. Push this repo to GitHub. It must be **public**, because the app reads releases without a token.
2. Add these 4 repository secrets (Settings → Secrets and variables → Actions). The values are in `keystore/GITHUB_SECRETS.txt`:
   `BREAK_KEYSTORE_BASE64`, `BREAK_KEYSTORE_PASSWORD`, `BREAK_KEY_ALIAS`, `BREAK_KEY_PASSWORD`
3. Every push to `main` builds a signed APK and publishes the release `build-N`.
4. Install the APK from that first release on your phone. From then on, Break detects each new push and offers to update.

⚠️ Back up `keystore/` somewhere safe and never commit it (it is gitignored). If the key is lost, installed copies can never be updated.

## Credits
GeneralUser GS (S. Christian Collins) · TinySoundFont (MIT) · Oboe (Apache 2.0) · Manrope (OFL)
