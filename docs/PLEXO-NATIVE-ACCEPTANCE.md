# Android Plexo native acceptance

This record separates software verification from installation, native UI, card and system-consumer acceptance. It covers the approved Android Plexo presentation and recognition package. Production deployment and physical-card acceptance have not occurred.

## Candidate and scope

- Checkout: `.cardkey-worktrees/android-plexo-native-20261014`; branch `feat/plexo-native-workspace`.
- Fresh default base: `1d5f46ab7ca6ae77d5c89769dd6d73c946510354`, including delivered SSH and lifecycle packages.
- Frozen software candidate: `7babf2942a584211355d42cc856a76369cb57023`.
- Runtime revision: `5a876028df201288d176ed55b331e58dad405e85`. Application source is identical through the final test-only corrections. This documentation increment does not change runtime or tests.

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

## Matching build pair

Both artifacts were built from the frozen software candidate:

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

The existing focused instrumentation class is `cc.cardkey.ui.WorkspaceAcceptanceTest`. It exercises a 360dp synthetic `ManagerScreen` host, no-card navigation and a real keyboard/secure-window case. It does not establish real recognition, transport, provider system invocation, card mutation or login acceptance. Before execution, its no-card navigation test needs selector/prerequisite adaptation: it still expects a disabled CSR action before recognition, while the approved consumer hides that action. The keyboard test supplies an explicit synthetic FIDO recognition snapshot. This source inspection is not a native test result.

After authorized device testing, close the tested apps and read back their exit. Keep failed or unavailable conditions pending with their evidence. Physical-card writes, iOS provisioning, account/system acceptance and production deployment remain outside this software checkpoint.
