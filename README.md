# CardKey for Android

A native Kotlin and Jetpack Compose Material 3 manager for FIDO2 and PIV smart cards. The Android application has its own public protocol implementation and builds without private repositories, applet binaries, provisioning secrets, or private CI artifacts.

The application targets Android 9 and later. NFC uses Android IsoDep. The USB adapter supports the Identiv uTrust 2700R (`04e6:5810`) when its CCID descriptor advertises APDU exchange and its active card parameters identify T=0 or T=1. Physical reader and phone qualification must be recorded separately from a successful build.

## Build

Install JDK 17, Android SDK platform 36, and build tools 35.0.0. Set `ANDROID_HOME` to the SDK directory, then run:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. CI uses public Ubuntu runners, public Maven repositories and synthetic tests. No hardware or signing credentials are required by CI.

## Management workflows

- FIDO2: authenticator information, retries, credential capacity, PIN set/change, resident RP and credential enumeration, credential deletion/user updates, always-UV, minimum PIN length, and provisioned enterprise-attestation activation.
- Diagnostics: CTAP registration/assertion and U2F signature verification; `credProtect`, `credBlob`, `hmac-secret`, `minPinLength`, `largeBlobKey`, and bounded encrypted large-blob access.
- PIV: information, slot metadata, public keys and certificates; PIN/PUK changes and unblock; 3DES/AES management authentication and key changes; RSA-2048/P-256/P-384 generation; verified card-signed CSR export; matching certificate import/readback; signature, ECDH and RSA-decryption checks.
- PIV reset only when the card already reports both PIN and PUK blocked. The app never consumes retries to reach that state. Blocking both has already erased keys in the supported CardKey profile.

Confirmation and status wording is subject to Owner approval. While that batch is pending, the public-read screens are active and operations requiring confirmation remain disabled. Protocol services and synthetic tests are implemented independently; this is not a claim that full device acceptance has passed. [Copy review list](docs/COPY-PROPOSAL.md)

## Card and session rules

PIV private operations, PIN/PUK checks, administration and writes require contact transport. NFC and unknown transports are rejected before a sensitive PIV command is transmitted. Certificate import compares the certificate public key with fresh on-card slot metadata before authentication and writing; certificate correspondence does not establish CA trust.

The supported CardKey profile uses 6–8 ASCII digits for both PIN and PUK, padded with `FF` to eight bytes. Its PUK rule is a profile-specific restriction, not a statement about every PIV implementation.

Each operation owns one session. PIN tokens and administrative authorization are never cached across operations. A lost response is not success and does not trigger replay. A write accepted before readback fails remains distinct from a command whose dispatch failed. Cancelling a request does not undo a command already sent.

When the authenticator requires physical presence, the session ends. The app does not reconnect with reset or power-cycle the card to satisfy presence. USB close releases the interface without sending power-off. FIDO reset is unavailable; applet replacement belongs to a separate desktop provisioning workflow. AAGUID, attestation keys and enterprise allowlists are not daily management controls.

Android Credential Provider integration is a later independent package. The current app is a manager and diagnostic client; it does not register an unfinished system-provider service or claim acceptance by an external website/account.

## License and validation

Original application code is licensed under [Apache-2.0](LICENSE). Dependencies, pins and licenses are listed in [DEPENDENCIES.md](docs/DEPENDENCIES.md). See [VALIDATION.md](docs/VALIDATION.md) for the software/hardware acceptance boundary.
