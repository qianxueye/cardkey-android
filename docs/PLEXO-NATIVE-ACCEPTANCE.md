# Android Plexo native acceptance

This record separates software verification from installation, native UI, card and system-consumer acceptance. It covers the approved Android Plexo presentation and recognition package. Production deployment and physical-card acceptance have not occurred.

## Candidate and scope

- Checkout: `.cardkey-worktrees/android-plexo-native-20261014`; branch `feat/plexo-native-workspace`.
- Fresh default base: `1d5f46ab7ca6ae77d5c89769dd6d73c946510354`, including delivered SSH and lifecycle packages.
- Verified software candidate: `248fea85ad6516cdae78a7872ffbc09f9170107d`.
- Runtime revision: `c0c9cd75b34cbc355b8b95aaf57d2093101ba648`; the review fix changes only provider recognition policy. Test-only successors retain that runtime. This evidence update changes no runtime or tests.
- Instrumentation expectation alignment: `8f65236f0aa587b78b479bb8f8f5a609aa5d8e82`; its package/lint gate passed in 291 s without device execution. Root supplied official build success for that candidate, run `38659600989`.

The package provides object-first FIDO/PIV navigation, compact detail/back and wide list/detail, explicit task selection, captured receipts and exports, technical disclosure, native light/dark presentation, and provider/CA/TLS reflow. Object and task browsing does not dispatch. An explicit recognition action dispatches a public INFO read while preserving the prospective task and slot.

Card secrets require a current matching last-observed family on the selected route. Recognition does not prove continued physical presence or card identity. Route, permission and lifecycle retirement clear secrets and pending confirmations. Narrow prepared-file exceptions retain only correctly owned public preparation; fresh signing or credential-verification authority remains necessary. Provider recognition retains its own request and held transport until final confirmation.

## Executed software evidence

| Evidence | Executed scope and result | Wall time |
| --- | --- | --- |
| Full semantic gate at `abcf9d29c791b6a6bdbc233e9454ef5d860e49ec` | 838 tests / 79 suites: 837 passed, one `TlsActivityTest` failed, zero errors or skips. The failing test still entered PIN before recognition. | 221 s |
| Final affected gate at `7babf294` | 39 tests / five suites, zero failures, errors or skips. `ManagerScreenLayoutTest` 18; `PlexoWorkspaceTest` 9; `TlsPanelTest` 3; `EnrollmentPanelTest` 6; `TlsActivityTest` 3. Application APK, instrumentation APK and lint passed. | 703 s |

The final correction changes only `TlsActivityTest`. It performs the actual public PIV_INFO path against the existing synthetic card before PIN input and retains the original input, SAF, pause/recreation and imported-bundle assertions. Exact public command assertions exclude authentication and mutation. Root authorized reuse of the 837 unaffected full-run passes plus the affected rerun. There was no single final 838/838-green invocation.

Earlier recognition fixture repairs passed their three affected suites: 23 tests, zero failures, errors or skips, 304 s. The full semantic run subsequently included those suites. Existing protocol, CA, CMS and OpenSSH evidence remains separate from installed or physical acceptance.

Final lint: zero Error/Fatal findings; ten established warnings (`GetInstance` one, `TrustAllX509TrustManager` nine); zero `UnusedResources`. Unused resource definitions were removed, not suppressed. Builds do not prove execution of native instrumentation.

Local evidence retained by the coordinator:

- Full XML: `/private/tmp/cardkey-android-plexo-recognition-full-red-xml`; log: `/private/tmp/cardkey-android-plexo-recognition-full.log`.
- Final XML and lint report: `/private/tmp/cardkey-android-plexo-recognition-final-xml`; log: `/private/tmp/cardkey-android-plexo-recognition-final.log`.
- Recognition repair XML: `/private/tmp/cardkey-android-plexo-recognition-repair-xml`; log: `/private/tmp/cardkey-android-plexo-recognition-repair.log`.

## Provider review-fix verification

Two posted P2 findings affected optional user verification and optional largeBlob writes. The policy now offers PIN only when client PIN, PIN/UV auth token support, protocol 1 or 2, and force-change state permit it. Optional blob PIN follows that usable capability. Required verification, always-UV, PRF and protected-create fences retain their existing rejection behavior. The held request/session, final confirmation, zero-authentication recognition probe and no-replay behavior remain unchanged.

At `f695d1f0c12ac0f681a407bd9c984e3a0f53c4f3`, the focused gate executed 185 tests across 19 suites: 184 passed, one new preferred/no-UV diagnostic assertion failed, zero errors/skips. Wall time was 658 s; summed suite time 61.178 s. The runtime login succeeded; the test then called a signature diagnostic whose default requires UV. Signed test-only correction `248fea8` passes `requireUv=false` for that explicit preferred fallback, preserves RP/hash/key/signature/user-presence/allow-list checks, and adds rejection of the same assertion under the default mandatory-UV diagnostic.

The affected `cc.cardkey.provider.CardCredentialOperationTest` then passed 21/21, zero failures/errors/skips, 3.994 suite seconds; remaining application APK, lint and instrumentation APK tasks passed in the same 361 s gate. Root authorized reuse of the other 164 focused passes. This establishes the 185-test focused coverage through the initial execution and its test-only repair; there was no single final 185/185-green invocation. The prior full 838-test execution and 39-test affected repair remain separate, retained evidence for untouched behavior.

Exact selected classes:

| Package | Classes |
| --- | --- |
| `cc.cardkey.provider` | `PlexoProviderTest`, `ResponseEncoderTest`, `CallerVerifierTest`, `ProviderExtractionLifecycleTest`, `ProviderRecognitionActivityTest`, `HttpsAssetLinksFetcherTest`, `CardCredentialOperationTest`, `ProviderPrfSelectionTest`, `ProviderRecognitionTest`, `ProviderLifecycleTest`, `TypedProviderWorkflowTest`, `ProviderPresenceActivityTest`, `ProviderInputLifecycleTest`, `ProviderExtensionsTest`, `RequestParserTest`, `ProviderPresenceProgressTest` |
| `cc.cardkey.protocol.fido` | `FidoClientTest`, `FidoExtensionsTest`, `LargeBlobsTest` |

New cases exercise the actual held runner/get path with unusable optional PIN-token capabilities under protocols 1 and 2, signed allow-list login, supported optional blob write returning `written:false` after authorization denial, mandatory policy rejection, real PIN token MACs, and no authentication before final confirmation or request replay. No additional full unit run, device execution or physical action occurred.

Focused red XML: `/private/tmp/cardkey-android-plexo-provider-policy-red-xml`; log: `/private/tmp/cardkey-android-plexo-provider-policy.log`. Repair XML and final lint: `/private/tmp/cardkey-android-plexo-provider-policy-final-xml`; log: `/private/tmp/cardkey-android-plexo-provider-policy-final.log`. Final lint retains zero Error/Fatal, the ten established crypto warnings and zero unused resources.

## Matching build pair

Both artifacts were assembled from the reviewed runtime and verified at `248fea8`; the final repair changes only a unit-test diagnostic expectation:

| Artifact | Size |
| --- | ---: |
| `app/build/outputs/apk/debug/app-debug.apk` | 42,162,725 bytes |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2,521,108 bytes |

The final pair has not been installed or executed on a device. Sony `QV7701J3JG` was last reported keyguard-locked with an older installed application/test pair. Its exact installed identity has not been refreshed in this checkpoint. Do not infer current installation from these build outputs, bypass the lock, or change device settings.

## Remaining native acceptance

Root owns device availability readback, installation of one matching pair and native execution. Record candidate, installed pair, device/API/display conditions and observed results before marking a condition passed. No native condition below is currently claimed passed.

| Condition | Required observation |
| --- | --- |
| 360dp portrait and large font | Objects, detail/back, long names, tasks and captured receipts remain reachable; selection does not dispatch. Secure inputs are absent before matching recognition. |
| Landscape, wide display and real IME | Compact/wide navigation remains usable; focused input and run/cancel avoid keyboard/system areas; recognition or lifecycle retirement clears secrets and pending confirmation. |
| Light/dark and TalkBack | Required facts, conditions, selected/disabled controls, warnings and actions are readable and announced in usable order; hidden secrets are not announced. |
| Native permission and SAF | Permission denial, pending grant, detach/replacement, pause/recreation and old callbacks preserve request/route/export ownership and unknown-outcome handling. |
| Provider create/get | Actual invoking caller/RP, verified request, public recognition, held session, final confirmation and cancellation remain bound to the same request; no presentation replay. |
| CA/TLS and SSH | Public preparation and captured results remain available; secrets require the approved recognition prerequisites. Actual enrollment/import, signing, TLS and SSH login require their own authorization and evidence. |

The existing focused instrumentation class is `cc.cardkey.ui.WorkspaceAcceptanceTest`. It exercises a 360dp synthetic `ManagerScreen` host, no-card navigation and a real keyboard/secure-window case. It does not establish real recognition, transport, provider system invocation, card mutation or login acceptance. Its no-card navigation test now requires absent password inputs and CSR/import controls before recognition, disabled secure tasks with an available explicit public-read action, and reachable public CA preparation. The current TLS preparation label remains asserted. The keyboard test supplies an explicit synthetic FIDO recognition snapshot. This instrumentation-only alignment leaves runtime and unit evidence unchanged; its successor test APK passed the coordinator’s package/lint gate and the later provider review-fix package gate. It has not been executed on a device.

After authorized device testing, close the tested apps and read back their exit. Keep failed or unavailable conditions pending with their evidence. Physical-card writes, iOS provisioning, account/system acceptance and production deployment remain outside this software checkpoint.

## Operation completion haptics candidate

The `feat/operation-haptics-20261026` candidate starts from merged default revision `ddaa18449c4b59a5916ef95e2d4faa3186c2354c`. The manager emits a non-replaying completion event for a user-started FIDO/PIV operation, protected-PIV recognition or TLS settlement. Completed results request platform `CONFIRM`; failed results and unknown effects request `REJECT`. Cancellation and retired operations stay silent. MainActivity consumes events only while resumed, and calls `View.performHapticFeedback` only on a visible, focused view. Progress, cached receipts, recomposition and reader polling emit no completion event. The existing result, transport and PIN contracts are unchanged.

The UI-copy ledger records the tactile change; app-owned strings and layouts are unchanged. Targeted local validation passed: 58 tests across seven suites, with zero failures, errors or skips. Suites: OperationFeedbackTest (6), OperationFeedbackActivityTest (2), ManagerViewModelTest (13), ManagerResetViewModelTest (7), ProtectedPivViewModelTest (8), InventoryViewModelTest (17) and ConsumerManagerTest (5). The real Activity tests cover visible completion, focus loss, pause/resume and recreation; model tests cover event deduplication, repeated operations, failure/unknown effects, cancellation, progress and no replay. Debug APK assembly and lint passed. Lint retains 11 existing warnings: nine TrustAllX509TrustManager, one GetInstance and one PluralsCandidate, with zero errors. This is targeted software validation; no full-suite or installed-device result is claimed. The final gate took 1 minute 20 seconds. The earlier run stopped at a test declaration typo, corrected before this passing run.

Installed-device acceptance remains with the coordinator: verify success and failure feedback on the installed candidate for FIDO and PIV reads; check repeated operations, cancellation, cached receipts, background/resume and rotation; confirm silence when system touch feedback is disabled. Physical feel depends on the device. This package performs no installation, device, card or PIN action.
