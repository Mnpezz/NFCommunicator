# NFCommunicator

Offline Android app for one-message NFC cards. The app reads, writes, and clears only its own encrypted NDEF or raw MIFARE Classic payload formats.

## Product decisions in this scaffold

- One encrypted message per card.
- Offline only. No accounts, sync, or stored passwords.
- Encryption: AES-256-GCM with PBKDF2-HMAC-SHA256 derived keys.
- Clear card: overwrite NDEF tags with an empty NDEF message or zero the app's raw MIFARE Classic storage region.
- Broad compatibility target: any NFC tag Android can access through standard NDEF APIs, plus raw MIFARE Classic when the handset exposes that tech.

## Tag support

This implementation now prefers raw `MifareClassic` whenever the scanned tag exposes that tech on the device. Tags without `MifareClassic` support still use standard NDEF, and non-NDEF tags can still be NDEF-formatted when Android exposes `NdefFormatable`.

Raw MIFARE Classic support intentionally skips sector 0 and all sector trailer blocks. That avoids manufacturer data and ACL blocks while still giving the app a contiguous storage region for one encrypted message.

- **NDEF Discovery in Reader Mode**: NFC Reader Mode flags are configured to allow Android to perform standard NDEF/NDEF-formatable tag discovery (allowing compatibility with tags like MIFARE Ultralight that lack raw MIFARE Classic exposure on certain handsets).


## Build

1. Ensure `ANDROID_HOME` points to your SDK, for example `/home/user/Android/Sdk`.
2. For signed release builds, keep signing material outside the repo. Copy [keystore.properties.example](/home/user/NFCCommunicator/keystore.properties.example) to `~/.config/nfccommunicator/keystore.properties` and place the keystore next to it, or set `NFC_COMMUNICATOR_SIGNING_PROPERTIES` to another local-only path.
3. Run `./gradlew assembleDebug` for debug or `./gradlew assembleRelease` for a signed release when the external signing config is present.

## UX flow

- `Read`: hold the tag to the phone on the read screen. If the tag contains this app's encrypted message, the app caches it immediately; if a password is already entered it is tried automatically, and if not you can enter the password afterward to decrypt the cached message.
- `Write`: enter password twice, type a plain-text message, review the estimated encrypted size for both NDEF and raw MIFARE Classic storage, tap `Write to Card`, and scan the tag.
- `Clear Card`: available from both tabs and clears the active storage backend for the scanned tag.
- Pending scans lock the active form fields and tabs so the captured password/message cannot drift before the tag is processed.
- The app gives immediate haptic feedback on tag detection plus a success/error toast and vibration when the NFC operation completes.
- Password fields are cleared after each completed read, write, or clear operation, and the read screen lets you select or copy the decrypted message.
- When the last scanned tag exposes an exact capacity, the write screen shows the maximum unencrypted character count that fits on that backend and keeps a live character count for the message draft.
- Raw `MIFARE Classic` tag summaries now show both the app-usable storage estimate and the card's full reported geometry, such as `MIFARE Classic 1K`, sector count, and total bytes.
- The selected tab, write draft, and last scanned tag summary survive activity recreation, but passwords and decrypted message text remain memory-only and are not restored from saved state.
- Failure states now include a secondary diagnostic explanation when the app can narrow the cause to handset support limits, common-key authentication failure, payload incompatibility, or a lost tag during I/O.
- If a card is readable but does not contain one of this app's encrypted messages, the read flow now says that directly instead of falling back to a generic incompatibility message.
- The UI uses a dark-only Material 3 theme built from `#E87722` and darker/lighter orange-derived shades.
- Reader mode stays active while the app is in the foreground, so tags now produce passive detection feedback and update the last scanned tag summary even before `Read`, `Write`, or `Clear` is armed.
- On the `Read` tab specifically, passive detection now reads and caches compatible encrypted payloads automatically instead of waiting for a manual scan-arm action.
