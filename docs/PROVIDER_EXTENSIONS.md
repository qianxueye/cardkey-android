# Credential-provider extensions

The Android credential provider maps WebAuthn extension inputs to capabilities advertised by `authenticatorGetInfo`. This documents application behavior; it does not establish physical-card or relying-party acceptance.

| Input | Card request and result |
| --- | --- |
| `credProps` | Reports the actual requested and supported resident-key choice as `credProps.rk`. |
| `credentialProtectionPolicy` | Maps the three standard strings to CTAP `credProtect` values 1–3. Enforced policies 2 and 3 fail before credential creation when unsupported. |
| `credBlob` / `getCredBlob` | Maps bounded bytes / a boolean to CTAP `credBlob`. Creation data exceeding the advertised maximum is ignored as specified. Results remain in signed authenticator data; no invented client output is added. |
| `uvm` | Sends `true` only when advertised. Validates and returns the authenticator's 1–3 three-integer rows using WebAuthn Level 2 semantics. Level 3 removed this extension from its own definitions. |
| `prf` | Enables `hmac-secret` at registration and reports `enabled`. CTAP 2.1 does not evaluate creation-time salts, so registration does not claim `results`. Authentication requires user verification even when the RP discourages it, hashes each input with the WebAuthn PRF domain separator, uses PIN protocol 1 or 2, and returns base64url `results.first` and optional `second`. |
| `largeBlob` | Requires both `largeBlobKey` and `largeBlobs` capabilities. Registration also requires a resident credential; an empty `largeBlob` object uses `preferred` support semantics. Read/write uses credential-bound authenticated encryption and the CTAP large-blob array; keys are never returned to the RP. Writes report success only after readback. |
| `minPinLength` | Requests the CTAP registration extension only when advertised. Disclosure remains subject to the authenticator's RP allowlist. The provider does not synthesize a value from `getInfo`. |

Unknown optional extensions are ignored. Malformed supported extension inputs are rejected before card I/O. Enterprise attestation, AppID compatibility, and payment remain unsupported. Attestation privacy and RP/challenge/caller checks remain unchanged.

`evalByCredential` overrides the default PRF `eval` for its matching allow-list credential. When effective inputs differ, the provider first sends a UV-authenticated `getAssertion` with the original allow-list, `up: false`, and no extension inputs. CTAP 2.1 section 6.2.2 step 11 selects one matching credential when an allow-list is supplied. The provider validates that single response's RP, credential ID, UV/UP flags, optional user handle, and signature encoding before choosing its inputs. The preflight may report UP when the request used enabled built-in UV without a PIN token, as required by CTAP 2.1 section 6.2.2 step 8. The PIN-token path still rejects unexpected UP. It then sends one final `up: true` assertion restricted to that credential, with the selected PRF salts, in the same card session. The final credential must match; user handles must also match when present in both responses. Only the final assertion can be returned to the RP.

Malformed preflight output, card removal, authorization failure, or an unknown transport outcome aborts the operation before a final assertion is sent. Cancellation guards every subsequent APDU dispatch, including the gap after preflight, even if channel closure is advisory. A completed preflight remains a known effect; cancellation after a dispatched final assertion with no response retains an unknown outcome and cannot deliver a credential. No preflight is replayed, no reset is issued, and no secret output is requested during preselection. Uniform effective inputs use one normal assertion without a preflight. Allow-list assertions cannot start `getNextAssertion` enumeration; an advertised count other than one is rejected. Discoverable assertions without an allow-list retain contiguous account enumeration and the existing account chooser.

The low-level `GetAssertionRequest.userPresence` and diagnostic `verifyAssertion(requireUp)` parameters default to `true`. Explicit internal `false` requests allow non-login verification while preserving RP, UV, signature, and counter checks. Provider login responses always require UP.

Large-blob writes require exactly one allowed credential and cannot be combined with `read`, even `read: false`. Provider blob payloads and PRF input values are bounded to 65,535 bytes, within the existing 256 KiB request envelope. Missing blob support/key yields an empty read result or `written: false`. Corrupt storage, oversized decoded data, and non-cancellation read failures preserve the validated assertion with an empty `largeBlob` object. Cancellation still aborts. A transport failure known to occur before any blob fragment was accepted reports `written: false`. A later undispatched fragment or readback requires recovery; it cannot undo an accepted fragment or claim that the write failed. Unknown write outcomes abort the ceremony without replay or a success result.

Decrypted PRF and blob results remain in provider-only output objects. They are encoded only into the selected RP response and wiped after selection, cancellation, teardown, or operation failure. Raw large-blob keys and low-level PRF results are wiped after conversion. Decoded PRF inputs, credential blobs, and large-blob write inputs are wiped on operation completion or failure. Pending inputs are also wiped on cancellation, caller rejection, or activity destruction. An active operation retains ownership until its consumer exits; teardown cannot wipe its inputs while they are still in use. Registration-only PRF validation and rejected parsing paths release their decoded buffers. No extension result is added to account labels or diagnostic reports by this provider package.

## References

- [W3C WebAuthn Level 3 Recommendation, 25 August 2026: extension definitions and PRF test vectors](https://www.w3.org/TR/2026/REC-webauthn-3-20260825/#sctn-extensions)
- [W3C WebAuthn Level 2 UVM definition](https://www.w3.org/TR/webauthn-2/#sctn-uvm-extension)
- [FIDO CTAP 2.1 extension definitions](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-20210615.html#sctn-extensions)
- [Android credential-provider integration](https://developer.android.com/identity/sign-in/credential-provider)

## Local verification

The four repository targets passed under JDK 17: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `assembleDebugAndroidTest`. The final run used `--offline --no-watch-fs --console=plain` and took 22.42 seconds. All 227 unit tests passed, with no failures, errors, or skips.

The regression suite covers both PIN protocols, built-in UV preselection with UP, rejection of UP on the PIN-token preflight path, RP/credential/user/signature checks, blob read failures, known undispatched writes, failed readback after accepted writes, and unknown-write non-replay. It also covers input cleanup after success, failure, cancellation, contention, connection failure, and Activity destruction, plus retention until an active consumer exits.

Four new regression tests failed against the prior implementation and passed after the fixes. The two Activity lifecycle tests also failed against the prior Activity. Empty registration `largeBlob` objects retain the current Recommendation's preferred semantics, tested with and without authenticator support.

Android-test APK compilation is not an emulator/device test run. Physical transport, browser/native RP interoperability, and account acceptance remain separate qualification steps.
