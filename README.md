# CardKey for Android

A native Kotlin and Jetpack Compose Material 3 manager for FIDO2 and PIV smart cards, with an interactive Android Credential Provider. The Android application has its own public protocol implementation and builds without private repositories, applet binaries, provisioning secrets, or private CI artifacts.

The application targets Android 14 and later. NFC uses Android IsoDep. USB FIDO HID interfaces with matching 64-byte input/output reports use direct CTAP2 and U2F interrupt transfers for both the Manager and Credential Provider; PIV is unavailable on this route. [CTAPHID scope and ownership](docs/CTAPHID-TRANSPORT.md) The USB adapter handles the Identiv uTrust 2700R (`04e6:5810`) through reader-managed APDU exchange or host-managed T=1 TPDU exchange. The recorded TPDU profile (`bcdDevice=0202`, features `0x100BA`) requires valid current T=1 parameters, successful link synchronization, and an acknowledged IFSD of 32 before any APDU. T=0 TPDU exchange is unsupported. Physical reader and phone qualification must be recorded separately from a successful build. [Transport scope](docs/T1-TRANSPORT.md)

## Build

Install JDK 17, Android SDK platform 36, and build tools 35.0.0. Set `ANDROID_HOME` to the SDK directory. The enrollment contract tests also require Smallstep CLI 0.31.0 and step-ca 0.30.2; set `CARDKEY_STEP_CLI` and `CARDKEY_STEP_CA` to their executable paths. [Enrollment and test setup](docs/PIV-ENROLLMENT.md) Then run:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. CI uses public Ubuntu runners, public Maven repositories and synthetic tests. No hardware or signing credentials are required by CI.

## Management workflows

- FIDO2: authenticator information, retries, credential capacity, PIN set/change, resident RP and credential enumeration, credential deletion/user updates, always-UV, minimum PIN length, and provisioned enterprise-attestation activation.
- Diagnostics: CTAP registration/assertion and U2F signature verification; `credProtect`, `credBlob`, `hmac-secret`, `minPinLength`, `largeBlobKey`, and bounded encrypted large-blob access.
- PIV: information, slot metadata, public keys and certificates; PIN/PUK changes and unblock; 3DES/AES management authentication and key changes; RSA-2048/P-256/P-384 generation; verified card-signed CSR export; matching certificate import/readback; bounded raw-file signing with independent verification and `signature.bin` export; ECDH and RSA-decryption checks. [File-signing scope](docs/PIV-FILE-SIGNING.md)
- PIV／PKI: CN-only P-256 slot 9A client-certificate enrollment with a selected step-ca root, CSR-bound administrator token, PKIX/profile checks and explicit certificate import/readback. [Enrollment scope](docs/PIV-ENROLLMENT.md)
- App-local PIV TLS qualification: one TLS 1.3 request using the imported P-256/9A identity, a fresh PIN and a separately selected server root. Qualification requires a bodyless HTTP 204 from the chosen endpoint; cancellation preserves card effects and uncertain APDU outcomes. [TLS scope and tests](docs/PIV-TLS-QUALIFICATION.md)
- PIV reset only when the card already reports both PIN and PUK blocked. The app never consumes retries to reach that state. Blocking both has already erased keys in the supported CardKey profile.

The CardKey PIV slot profile is 9A, 9C, 9D, 9E and 82–8C; 9B remains the management-key reference. Valid empty-slot metadata retains the returned PIN policy, touch policy and origin without inventing an algorithm or public key. Private-key operations reject empty slots before PIN verification or management authentication.

Management actions use the approved Chinese confirmation and result messages. PIN/PUK-sensitive PIV operations read live retry counts before verification; a last attempt that could erase keys requires an additional confirmation and a second check of those facts in the same session. Unknown writes block further mutations. For HID, routine inspection cannot resolve an unknown write because a USB route does not establish card identity. These controls do not replace physical device acceptance. [Copy scope](docs/COPY-PROPOSAL.md)

## Card and session rules

The manager screens require contact transport for PIV private operations, PIN/PUK checks, administration and writes. NFC and unknown transports are rejected before these sensitive manager commands are transmitted. The separate [CS7 protocol API](docs/CS7-WIRE.md) supports protected PIN and private-key operations after explicit issuer/card enrollment and authenticated pairing; it does not add enrollment storage or a manager workflow. Administration and PUK recovery remain contact-only even with a paired secure context. Certificate import compares the certificate public key with fresh on-card slot metadata before authentication and writing; certificate correspondence does not establish CA trust.

The supported CardKey profile uses 6–8 ASCII digits for both PIN and PUK, padded with `FF` to eight bytes. Its PUK rule is a profile-specific restriction, not a statement about every PIV implementation.

Each operation owns one session. PIN tokens and administrative authorization are never cached across operations. A lost response is not success and does not trigger replay. A write accepted before readback fails remains distinct from a command whose dispatch failed. Cancelling a request does not undo a command already sent.

When the authenticator returns a terminal physical-presence requirement, the session ends. HID KEEPALIVE responses remain part of the current operation. The app does not reconnect with reset or power-cycle the card to satisfy presence. USB close releases the interface without sending power-off. FIDO reset is available only on a selected HID attachment with observed CTAP2 support and the approved destructive confirmation. It sends one reset and reads current status after acknowledgement, without PIN exchange or replay. APDU/NFC reset remains unavailable; applet replacement belongs to a separate desktop provisioning workflow. [Reset scope](docs/manager-fido.md) AAGUID, attestation keys and enterprise allowlists are not daily management controls.

The Android Credential Provider creates and retrieves ES256 passkeys using the card after explicit confirmation. Enable CardKey through its provider-settings entry. Get uses an interactive system Action; it does not maintain an on-phone credential database. Native app requests require a verified Digital Asset Links association; the initial delegated browser policy accepts Chrome Stable with its pinned release signer. [Provider security, privacy and acceptance](docs/CREDENTIAL-PROVIDER.md)

## License and validation

Original application code is licensed under [Apache-2.0](LICENSE). Dependencies, pins and licenses are listed in [DEPENDENCIES.md](docs/DEPENDENCIES.md). See [VALIDATION.md](docs/VALIDATION.md) for the software/hardware acceptance boundary.
