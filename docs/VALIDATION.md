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

Nine Android instrumentation tests compile into `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. They cover system request extraction, option/caller binding, rejected direct invocation, recreation, manifest permissions, scripted card registration/assertion and Android response parcel round trips. The scripted RP verifies returned signatures independently. Root first ran the nine tests on Sony XQ-DQ44 / API 35: eight passed; the recreation test hit ActivityScenario's 45-second wait for RESUMED, whereas the provider intentionally finishes after recreation. The corrected harness observes a distinct restored activity finishing before resume and checks the typed cancellation result. That harness uses the installed Chrome Stable base APK signing identity, validated against the production allowlist, to avoid DAL networking. The test obtains the installed APK path through the instrumentation shell and inspects that archive; an app-scoped package lookup is filtered by Android package visibility on the Sony. The corrected test passed on that physical phone in 3.243 seconds. The other eight tests were unchanged and their earlier passes are reused; this is not a claim of a new nine-test full run on the final APK.

The packaged application manifest includes NFC, Internet and AndroidX's signature-scoped internal receiver permission. Unused biometric permissions from the Credentials dependency are removed. The seven approved provider messages match the reviewed batch, and `app_name` supplies the product display name.

Both APKs are installed on the physical Sony and the nine distinct instrumentation tests have applicable passing device evidence. Provider physical acceptance remains separate: verify system provider enablement and picker behavior, validate native DAL and Chrome Stable flows, and qualify real NFC/USB cancellation, presence, PIN and account operations. Scripted-card and Android plumbing results do not establish those consumer results.

## Manager diagnostic signature acceptance

The manager rejects an invalid CTAP attestation or assertion signature and an invalid U2F registration or authentication signature with the existing card-response failure. It checks signatures before replacing a cached credential or advancing its verified counter. An accepted card command can still have changed card state; signature failure preserves that effect and does not retry the command. Unsigned `none` attestation remains supported and does not imply an attestation signature was verified.

Six manager regressions use synthetic EC keys and actual signed APDU responses. Four reject cryptographically invalid signatures and confirm that the prior credential or counter remains usable. The other two cover unsigned attestation and an unknown registration result after a lost response, without retry or caching a new credential. Against the original manager, exactly the four invalid-signature tests fail; the red-proof run took 8 seconds (8.50 seconds wall time).

The required `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --no-watch-fs` gate passed in 25 seconds. All 182 JVM tests passed, including the six new regressions; summed test execution was 0.963 seconds, with 0.292 seconds for the new class. Both APKs compiled. This package has no new device or physical-card acceptance result.
## Manager review regressions

Synthetic workflow tests reject invalid CTAP and U2F registration/assertion signatures before replacing cached credentials or counters. `none` attestation still permits a subsequent verified assertion. Credential deletion and user-name updates enumerate the target and check the resulting state with the same credential-management token. User IDs come from that enumeration; accepted mutations with missing or mismatched readback remain failures without automatic replay. Requested HMAC output must be present on every assertion. Blob-write attempts preserve the reusable imported bytes on success and failure.

JVM Android framework tests cover an absent or disabled NFC adapter, enabling NFC for a new operation, and disabling it while an operation is waiting. Cancellation-race tests preserve known PIN failures, block subsequent APDU dispatch, and retain the uncertain effect of a write interrupted by channel closure. Robolectric and coroutine test dispatchers provide these checks without phone or card access.

On 2026-10-11, the complete manager review candidate passed `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` in one invocation: 29 seconds reported by Gradle, 29.88 seconds wall time. All 137 JVM tests across 21 suites passed with no failures, errors, or skipped tests; summed test execution time was 2.714 seconds. Lint reported zero errors and 11 warnings: nine dependency/tool-version notices, the existing unused Bouncy Castle TLS helper, and the existing base monochrome-icon warning. No gate, lint baseline, or suppression was weakened. Both debug APKs were built; no instrumentation tests, device installation, or physical card acceptance were performed.

The approved six-string Android batch is active. Regression tests require blob overwrite confirmation with and without a PIN, distinct capacity/recovery/rejection reasons, and distinct NFC unavailable/disabled reasons alongside the no-dispatch result.

## Provider review round (2026-10-11)

The provider review candidate passed `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --no-daemon` in one invocation: 2 minutes 38 seconds reported by Gradle and 159.68 seconds measured wall time. All 206 JVM tests across 28 suites passed with no failures, errors or skipped tests; summed test execution was 17.801 seconds. This includes API 34 Robolectric coverage of system request extraction and Activity cancellation results, abandoned-offer capacity, safe reselection, stale claim fencing and cancellation during connection. Lint reported zero errors, 16 warnings and one hint: 15 dependency/tool-version notices, the existing unused Bouncy Castle TLS helper, and an optional Fragment KTX suggestion. Both debug APKs built. No gate, lint baseline or suppression was weakened.

The initial targeted run reproduced rejection of the 65th abandoned offer and missing response attachment fields (14 tests, three failures, two seconds). The fixes also add the creation transports required by the pinned Chromium parser. Source checks include that parser, a fixed CredManHelper revision, and AndroidX 1.6.0 encoders. These checks do not execute Chromium or the system selector. No device installation, instrumentation execution, physical NFC/USB operation or browser/account acceptance was performed in this round.
