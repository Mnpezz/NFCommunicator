# NFCommunicator ProGuard / R8 rules
# Applied to release builds alongside proguard-android-optimize.txt.

# ── Crypto ─────────────────────────────────────────────────────────────────────
# javax.crypto.* and java.security.* are Android system classes; R8 never touches
# them. No keep rules are needed for those packages.
# AEADBadTagException is caught by name in SecureMessageCodec — keep it so that
# the catch clause resolves correctly after obfuscation.
-keep class javax.crypto.AEADBadTagException { *; }

# ── NFC tech classes ───────────────────────────────────────────────────────────
# android.nfc.tech.* is part of the Android system framework; R8 does not shrink
# system classes. The keep rule below is retained as explicit documentation of the
# dependency and as a safeguard against future local stubs or wrappers.
-keep class android.nfc.tech.** { *; }

# ── Domain exception hierarchy ─────────────────────────────────────────────────
# Catch clauses rely on the exact runtime type of these exceptions; shrinking
# must not rename or remove them.
-keep class dev.alsatianconsulting.NFCommunicator.domain.InvalidPayloadException { *; }
-keep class dev.alsatianconsulting.NFCommunicator.domain.MifareHeaderNotFoundException { *; }
-keep class dev.alsatianconsulting.NFCommunicator.domain.MifarePayloadLengthException { *; }
-keep class dev.alsatianconsulting.NFCommunicator.domain.MifarePayloadTruncatedException { *; }
-keep class dev.alsatianconsulting.NFCommunicator.domain.InvalidPasswordException { *; }

# ── Jetpack Compose ────────────────────────────────────────────────────────────
# The Compose compiler plugin generates stable metadata that R8 must not remove.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }
