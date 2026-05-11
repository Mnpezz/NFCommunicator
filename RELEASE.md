# NFCommunicator 1.0 Release

**Date:** 2026-05-11
**Version name:** 1.0 · **Version code:** 1
**Package:** `dev.alsatianconsulting.nfcommunicator`
**Developer:** Alsatian Consulting · geoff@alsatian.consulting

---

## What it does

NFCommunicator stores one AES-256-GCM encrypted plain-text message on an NFC tag and reads it back using a shared password. There is no account, no cloud sync, and no network access. The app supports NDEF and raw MIFARE Classic tag paths, with automatic backend selection on compatible handsets.

Core flows: **Read** (scan tag → enter password → decrypt and view), **Write** (enter password → confirm → compose message → write encrypted payload), **Clear** (erase the app's payload from a scanned tag).

---

## Release artifacts

| File | SHA-256 |
|------|---------|
| `NFCommunicator-1.0-release.apk` | `b4c76a9480a922b94bfe498c8502c38ddae4a74eb35675fae693760d6e5be9b0` |
| `NFCommunicator-1.0-release.aab` | `c4baf5eb3bc97683048dc2de90cf15b8ddbba479175d4c6b02f04b9aafb0bbc5` |

All artifacts in `playstore/release/1.0/`. Verify with:

```
sha256sum -c playstore/release/1.0/SHA256SUMS.txt
```

---

## Build configuration

| Setting | Value |
|---------|-------|
| Compile SDK | 36 (Android 16) |
| Target SDK | 36 |
| Min SDK | 26 (Android 8.0 Oreo) |
| Build type | release |
| Minification | R8 (enabled) |
| Resource shrinking | enabled |
| `FLAG_SECURE` | enabled in release, disabled in screenshot build type |

**Signing:** master Android keystore (`master-android-signing.jks`), key alias `master`.
Properties loaded via `NFC_COMMUNICATOR_SIGNING_PROPERTIES` env var or `~/.config/nfccommunicator/keystore.properties`.

To reproduce:

```
NFC_COMMUNICATOR_SIGNING_PROPERTIES=/path/to/nfcommunicator-signing.properties \
  ./gradlew assembleRelease bundleRelease
```

---

## Security properties

- Encryption: AES-256-GCM
- Key derivation: PBKDF2-HMAC-SHA256
- Per-message random salt and nonce — no two ciphertexts are identical
- Password is never stored or persisted by the app
- `allowBackup="false"` and `dataExtractionRules` prevent OS-level backup of app data
- `FLAG_SECURE` blocks screenshot/screen-recording capture in production builds

---

## Permissions

- `android.permission.NFC` — required for all tag operations
- `android.permission.VIBRATE` — haptic feedback on scan events

---

## Tag compatibility

- NDEF (read/write/clear)
- Raw MIFARE Classic (read/write/clear, where exposed by the handset)
- Prefers raw MIFARE Classic when both paths are available
- Clear diagnostics surfaced for: unsupported tags, wrong passwords, payload format errors, tag-loss events

---

## Play Store submission

- AAB: `playstore/release/1.0/NFCommunicator-1.0-release.aab` — upload to Play Console
- APK: `playstore/release/1.0/NFCommunicator-1.0-release.apk` — direct install / sideload
- Store metadata: `playstore/metadata/`
- Graphics: `playstore/feature-graphic-1024x500.png`, `playstore/icon-512x512.png`
- Screenshots: `playstore/screenshots/selected/`
- Privacy policy: `playstore/privacy-policy.html`
