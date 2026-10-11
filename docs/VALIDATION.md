# Validation boundary

Software checks use original synthetic protocol inputs and in-process card models. They do not access a phone, USB reader, NFC controller, physical card, private repository or account.

Required local/CI gate:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

Covered boundaries include canonical and malformed CBOR/TLV; PIN protocols and permission binding; APDU continuation; management authentication; CSR and certificate correspondence; signature/ECDH/RSA checks; contact-only PIV enforcement; CCID descriptor, sequence, length and time-extension handling; cancellation; lost responses; no replay; and PIN retry effects separate from intended-write acceptance.

The application never claims that a passing local signature establishes certificate-chain trust, FIDO certification or external-account acceptance. U2F and CTAP diagnostics use synthetic `cardkey.example` identities. Diagnostic registration can create credentials and therefore requires confirmation.

Physical acceptance remains open until the hardware coordinator records:

- APK installation and Material 3 screen behavior on the intended Android device.
- USB permission, descriptor/current-protocol readback, public reads, and contact operations on the qualified reader/card.
- NFC public FIDO/PIV reads and pre-transmission refusal of sensitive PIV requests.
- Removal, cancellation, physical-presence-required handling, and actual recovery without synthetic host reset.
- Each enabled management workflow and the approved confirmations on the physical device.

PINs, management keys, card-private keys and raw physical-card logs must not be added to this public repository. Hardware evidence may identify public software versions and test outcomes without publishing secrets or private fixtures.

The JSON vectors in `docs/vectors/apdu-v1.json` are public standard examples for cross-platform behavior checks. Their bytes are synthetic; they are not card captures.

## Local software result

On 2026-10-10, `assembleDebug testDebugUnitTest lintDebug` completed successfully in 8 seconds after dependency caches were populated. All 113 JVM tests passed (1.250 seconds of test execution), including confirmation cancellation/duplicate submission, live final-retry predicates and stale-fact rejection, outcome wording, malformed write results, and a power-free CCID removal probe. Android lint reported zero errors and 9 warnings: version-update notices for deliberately pinned dependencies, the unused Bouncy Castle TLS helper, and a monochrome-icon warning on the base icon resource (an Android 13 icon resource supplies it). No lint baseline or error suppression was added. Initial public dependency resolution and the first APK build took 2 minutes 34 seconds.

This result covers the local implementation; physical phone/card qualification remains separate.

## Credential-provider software result

On 2026-10-10, the normal `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` gate passed. The complete source/dependency run took 1 minute 42 seconds. All 172 JVM tests passed, including the original 113 manager regressions and 59 provider tests; summed test execution was 4.087 seconds. A final manifest/license-only run passed in 59 seconds and reused the unchanged JVM test result. No lint baseline or error suppression was added.

The final lint report has zero errors, 15 warnings and one suggestion. Warnings concern deliberately pinned dependency versions, the unused Bouncy Castle TLS helper, and an obsolete empty local resource folder that has since been removed. The suggestion recommends optional Fragment KTX extensions. Fragment is constrained to 1.8.9 to satisfy Activity Result API compatibility. Provider HTTPS uses platform TLS trust; the Bouncy Castle permissive TLS helper is not invoked.

Nine Android instrumentation tests compile into `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. They cover system request extraction, option/caller binding, rejected direct invocation, recreation, manifest permissions, scripted card registration/assertion and Android response parcel round trips. The scripted RP verifies returned signatures independently. These tests were compiled, not executed on a phone by the implementation agent.

The packaged application manifest includes NFC, Internet and AndroidX's signature-scoped internal receiver permission. Unused biometric permissions from the Credentials dependency are removed. The seven approved provider messages match the reviewed batch, and `app_name` supplies the product display name.

Provider physical acceptance remains separate: root must install both APKs, run instrumentation, verify system provider enablement and picker behavior, validate native DAL and Chrome Stable flows, and qualify real NFC/USB cancellation, presence, PIN and account operations. The implementation does not claim any of these results from the software gate.
