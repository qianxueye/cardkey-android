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

## Extended manager workflows

The manager adds explicit RP disclosure replacement, enterprise request mode selection, credential protection policies, requested UVM results, private secret viewing and protected content export. Large Blob access obtains a fresh UV/no-UP credential proof and uses the verified fresh key in the same card session. Cancellation is checked before every manager APDU dispatch and immediately after the fresh proof; cancellation preserves the known proof/counter effect while preventing blob commands. Unknown final blob dispatch remains unknown and is never retried.

The final integrated `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --offline --no-watch-fs` gate passed in 27 seconds. All 214 JVM tests passed, with zero failures/errors/skips and 3.919 seconds of summed execution. Lint reports zero errors, 14 warnings and one hint; no baseline or suppression was added. Both application and instrumentation APKs compiled. The branch normally includes the verified provider dependency `9a09292`, whose default RP assertion still requires user presence.

The regressions cover minimum-PIN RP replacement/empty clearing/lower-bound rejection, all three credential protection policies, enterprise request mapping, requested UVM registration/assertion results and malformed/unsolicited output rejection, private secret wiping, consent cancellation, current-session blob proof, invalid signatures/key mismatch, encrypted empty content versus deletion, cancellation after successful proof and an unknown final blob write without retry. No physical Android/card or system credential-provider acceptance was performed by this package. Backend blob deletion remains outside the interface pending its separate consequence confirmation.

## Large Blob integrity recovery

Initial complete array reception treats an invalid or missing digest as the logical empty array, as required by [CTAP 2.1 section 6.10.2](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-errata-20220621.html) and [CTAP 2.2 section 6.10.2](https://fidoalliance.org/specs/fido-v2.2-ps-20250714/fido-client-to-authenticator-protocol-v2.2-ps-20250714.html). This logical recovery sends no write. An explicitly authorized update can replace the corrupt shared array. Valid-digest malformed CBOR and received/target capacity violations still fail.

Post-write verification compares the actual received bytes with the serialized target. It does not decode, recover or re-encode the response. A corrupt readback therefore fails even when the intended operation deleted the last entry and the target is empty. It preserves the accepted-write effect and sends no retry.

Red proof against the original runtime produced the expected initial-corruption failures. Adding logical recovery alone reproduced a false successful empty deletion; the dedicated post-write regression caught it. With strict raw readback, the final four-target gate passed in 21 seconds: 221 JVM tests, zero failures/errors/skips, 3.843 seconds of summed execution. All 11 Large Blob tests passed in 0.370 seconds. Lint remains at zero errors, 14 warnings and one hint. Both APKs compiled; no runtime wording, physical-card or account acceptance changed.

The Owner-approved `confirm_blobWrite` warning is now shown before manager Large Blob writes and on the provider confirmation page only when the request contains `largeBlobWrite`, including empty plaintext. Existing RP/caller, PIN, privacy and secret-output consent text is preserved. Refusal regressions verify that the manager's pending operation and the provider's cancelled runner dispatch neither a credential proof nor a blob command; they also verify the existing secret-input cleanup. The final four-target gate after this integration passed in 15 seconds: 224 JVM tests, zero failures/errors/skips and 2.416 seconds of summed execution. Lint remains at zero errors, 14 warnings and one hint; both APKs compiled. Physical screen interaction and card acceptance remain separate.

## Manager review regressions

Synthetic workflow tests reject invalid CTAP and U2F registration/assertion signatures before replacing cached credentials or counters. `none` attestation still permits a subsequent verified assertion. Credential deletion and user-name updates enumerate the target and check the resulting state with the same credential-management token. User IDs come from that enumeration; accepted mutations with missing or mismatched readback remain failures without automatic replay. Requested HMAC output must be present on every assertion. Blob-write attempts preserve the reusable imported bytes on success and failure.

JVM Android framework tests cover an absent or disabled NFC adapter, enabling NFC for a new operation, and disabling it while an operation is waiting. Cancellation-race tests preserve known PIN failures, block subsequent APDU dispatch, and retain the uncertain effect of a write interrupted by channel closure. Robolectric and coroutine test dispatchers provide these checks without phone or card access.

The functional parent integration retains the FIDO profile controls, provider extensions and approved Large Blob consent/recovery behavior. Credential updates use the enumerated user ID and the supported username field; client deletion/update readback reuses its management token without another manager PIN verification. Matching diagnostic context is invalidated before a mutation, including an unknown result. Imported content is copied into operation-owned buffers so cleanup does not consume reusable input. The parent NFC/cancellation channel is integrated while preserving known card failures, accepted-write effects and private output cleanup.

The focused compile and FIDO/provider/manager/cancellation/ViewModel regressions pass. The full APK/lint/instrumentation gate is deferred until the final approved parent-copy integration. No pending strings are included in this preparation checkpoint, and it is not a new device/card acceptance result.

Credential user-update readback treats absent and empty `name`/`displayName` as the same logical removal, following [CTAP 2.1 errata section 6.8.6](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-errata-20220621.html). The request still removes an omitted field; it never preserves an old nonempty value. User-ID bytes and requested nonempty names remain exact comparisons, and readback uses the same management token without replay. Both empty/absent direction regressions failed before the comparison-only fix. After it, all 78 selected FIDO/manager tests passed in 2 seconds, with zero failures/errors/skips and 0.460 seconds of summed execution. The tests also reject retained old names, nonempty mismatches and changed user IDs. The final APK/lint/instrumentation gate remains deferred to the final parent-copy integration.

The shared FIDO PIN encoder now applies Unicode normalization form C before code-point, NUL and UTF-8 length checks, as required by [CTAP 2.1 sections 6.5.1, 6.5.5.5 and 6.5.5.6](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-errata-20220621.html). Setting, changing and authenticating a PIN therefore use the same canonical bytes. A fixed 63-byte encoder buffer retains strict malformed-input and overflow rejection. The normalized mutable character array and complete encoder buffer are cleared in `finally`; existing callers still clear their original inputs and returned bytes. Java's standard `Normalizer` may create immutable temporary strings that cannot reliably be wiped. PIV encoding is unchanged.

The genuine red run against unchanged runtime produced seven failures among 12 PIN tests in 1 second. Independent cases cover canonical byte/hash equality, normalized code-point minimum, UTF-8 shrinking to exactly 63 bytes and expansion past that ceiling. The green focused compile/FIDO PIN/client/manager gate passed all 93 selected tests in 4 seconds, with zero failures/errors/skips and 1.761 seconds of summed execution. Both PIN protocols exercise set/change encryption, token hashes, caller-input erasure and uncertain token-response effects. No raw-PIN fallback, dependency, runtime copy, firmware or transport change was introduced; the final APK gate remains deferred.
On 2026-10-11, the complete manager review candidate passed `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` in one invocation: 29 seconds reported by Gradle, 29.88 seconds wall time. All 137 JVM tests across 21 suites passed with no failures, errors, or skipped tests; summed test execution time was 2.714 seconds. Lint reported zero errors and 11 warnings: nine dependency/tool-version notices, the existing unused Bouncy Castle TLS helper, and the existing base monochrome-icon warning. No gate, lint baseline, or suppression was weakened. Both debug APKs were built; no instrumentation tests, device installation, or physical card acceptance were performed.

The approved six-string Android batch is active. Regression tests require blob overwrite confirmation with and without a PIN, distinct capacity/recovery/rejection reasons, and distinct NFC unavailable/disabled reasons alongside the no-dispatch result.

## Completed approved-parent stack integration

The approved parent has been normally merged through the existing unmerged Android branches. Each stage passed `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --offline --no-watch-fs` independently. The local results are:

| Stage | Verified runtime commit | JVM tests | Summed test seconds | Gate seconds | Lint errors / warnings / hints |
| --- | --- | ---: | ---: | ---: | --- |
| PR2 provider | 56217ccc | 196 | 4.275 | 11 | 0 / 14 / 1 |
| PR3 status copy | 06113684 | 200 | 3.041 | 15 | 0 / 14 / 1 |
| PR4 signature verification | c92e34c6 | 200 | 2.472 | 18 | 0 / 14 / 1 |
| PR5 provider extensions | 6ec5268f | 218 | 2.611 | 13 | 0 / 1 / 0 |
| PR6 FIDO profile | 47db3ea6 | 254 | 3.003 | 9 | 0 / 14 / 1 |

Every stage had zero test failures, errors and skipped tests. Both APK targets compiled at every stage; instrumentation was not executed on a device. No lint baseline, suppression or gate was weakened. The parent raw-key overwrite warning applies to its lower raw-key management stages. PR6 retains the separately approved credential-bound corruption/recovery warning and secret-read/PIN consent. The final PR6 source includes the approved NFC/capacity/recovery/rejection mappings, username-only manager updates, immutable user-ID and same-token readback checks, reusable input ownership, Unicode PIN normalization, private provider outputs and origin verification, and known/unknown cancellation effects.

The final parent-copy integration gate is complete; earlier preparation notes about deferring that gate are historical. Remote publication/checks and physical phone/card/account acceptance remain separate. No private applet source or CAP artifact is needed by these Android gates.

## PR6 finding corrections

A failed fresh Large Blob proof now invalidates its cached diagnostic credential; the context is restored only after the complete proof and key checks succeed. A signed key mismatch therefore cannot be followed by an attempt that silently drops the original key binding. Accepted/unknown effects and no-replay behavior remain unchanged.

Result retirement clears owned secret outputs and protected plaintext before a new submission, reader switch, completed-result replacement or cancellation. An incoming result that retains the same buffer keeps its ownership; a separately authorized export copy is also preserved until its consumer clears it. Regressions cover all replacement paths and both ownership cases.

Requested assertion `credBlob` output uses the existing secret-read and reveal consent, secure display and private wiped output container. It is absent from ordinary hex rows, automatic exports and report text. Refusing consent sends no card commands, and an invalid signature exposes no private output. Registration retains its Boolean storage result and original registration consent without claiming that it reads secret output.

All three findings were reproduced against unchanged source: five regressions failed among 47 selected tests in 4 seconds. The focused green run passed those 47 tests in 6 seconds. The full four-target gate then passed 261 JVM tests in 118 seconds, with zero failures/errors/skips and 8.428 seconds of summed execution. After narrowing the new consent condition to assertion output and adding its registration-scope regression, the 20 affected confirmation tests passed in 0.106 seconds; the required APK/lint/instrumentation build targets passed with them in 23 seconds. Unchanged full-suite evidence was reused. Final lint has zero errors, 14 warnings and one hint; both APKs compiled. No new runtime wording, device/card qualification or remote publication is implied.
## Provider review round (2026-10-11)

The provider review candidate passed `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --no-daemon` in one invocation: 2 minutes 38 seconds reported by Gradle and 159.68 seconds measured wall time. All 206 JVM tests across 28 suites passed with no failures, errors or skipped tests; summed test execution was 17.801 seconds. This includes API 34 Robolectric coverage of system request extraction and Activity cancellation results, abandoned-offer capacity, safe reselection, stale claim fencing and cancellation during connection. Lint reported zero errors, 16 warnings and one hint: 15 dependency/tool-version notices, the existing unused Bouncy Castle TLS helper, and an optional Fragment KTX suggestion. Both debug APKs built. No gate, lint baseline or suppression was weakened.

The initial targeted run reproduced rejection of the 65th abandoned offer and missing response attachment fields (14 tests, three failures, two seconds). The fixes also add the creation transports required by the pinned Chromium parser. Source checks include that parser, a fixed CredManHelper revision, and AndroidX 1.6.0 encoders. These checks do not execute Chromium or the system selector. No device installation, instrumentation execution, physical NFC/USB operation or browser/account acceptance was performed in this round.
