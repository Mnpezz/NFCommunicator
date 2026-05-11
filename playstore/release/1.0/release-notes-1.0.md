# NFCommunicator 1.0 Release Notes

## Highlights
- Initial public release of NFCommunicator.
- Offline encrypted NFC messaging with one message per tag.
- Read, write, and clear flows for app-owned tag content.

## Security
- AES-256-GCM encryption.
- PBKDF2-HMAC-SHA256 key derivation.
- Random salt and nonce per encrypted message.
- No account requirement and no password persistence.

## Tag Support
- NDEF read/write/clear support.
- Raw MIFARE Classic read/write/clear support on compatible handsets.
- Automatic backend selection with preference for raw MIFARE Classic when available.

## UX and Diagnostics
- Passive tag detection while app is foregrounded.
- Read flow supports cached payload detection before decrypt attempt.
- Clear error diagnostics for unsupported tags, authentication failures, payload format issues, and tag-loss events.
- Dark orange theme and adaptive launcher icon.

## Verification
- Local suite passed for debug/release unit tests, lint, and assemble tasks.
- Real-card regression completed successfully.
