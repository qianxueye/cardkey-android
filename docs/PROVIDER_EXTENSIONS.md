# Credential-provider extensions

The Android credential provider maps WebAuthn extension inputs to capabilities advertised by `authenticatorGetInfo`. This documents application behavior; it does not establish physical-card or relying-party acceptance.

| Input | Card request and result |
| --- | --- |
| `credProps` | Reports the actual requested and supported resident-key choice as `credProps.rk`. |
| `credentialProtectionPolicy` | Maps the three standard strings to CTAP `credProtect` values 1–3. Enforced policies 2 and 3 fail before credential creation when unsupported. |
| `credBlob` / `getCredBlob` | Maps bounded bytes / a boolean to CTAP `credBlob`. Creation data exceeding the advertised maximum is ignored as specified. Results remain in signed authenticator data; no invented client output is added. |
| `uvm` | Sends `true` only when advertised. Validates and returns the authenticator's 1–3 three-integer rows using WebAuthn Level 2 semantics. Level 3 removed this extension from its own definitions. |
| `prf` | Enables `hmac-secret` at registration and reports `enabled`. CTAP 2.1 does not evaluate creation-time salts, so registration does not claim `results`. Authentication requires user verification even when the RP discourages it, hashes each input with the WebAuthn PRF domain separator, uses PIN protocol 1 or 2, and returns base64url `results.first` and optional `second`. |
| `largeBlob` | Requires both `largeBlobKey` and `largeBlobs` capabilities. Registration also requires a resident credential. Read/write uses credential-bound authenticated encryption and the CTAP large-blob array; keys are never returned to the RP. Writes report success only after readback. |
| `minPinLength` | Requests the CTAP registration extension only when advertised. Disclosure remains subject to the authenticator's RP allowlist. The provider does not synthesize a value from `getInfo`. |

Unknown optional extensions are ignored. Malformed supported extension inputs are rejected before card I/O. Enterprise attestation, AppID compatibility, and payment remain unsupported. Attestation privacy and RP/challenge/caller checks remain unchanged.

`evalByCredential` overrides the default PRF `eval` for its matching allow-list credential. The provider accepts a single allowed credential or multiple credentials with identical effective inputs. Different effective inputs fail as unsupported before card I/O. The card grants only one user-presence assertion per physical session; supporting different inputs requires account selection while retaining the card session, before the final assertion. The provider does not reset the card, replay an assertion, or substitute one account's PRF result for another.

Large-blob writes require exactly one allowed credential and cannot be combined with `read`, even `read: false`. Provider blob payloads and PRF input values are bounded to 65,535 bytes, within the existing 256 KiB request envelope. Missing blob support/key yields an empty read result or `written: false`. Transport failures with unknown write outcomes abort the ceremony without replay or a success result.

Decrypted PRF and blob results remain in provider-only output objects. They are encoded only into the selected RP response and wiped after selection, cancellation, teardown, or operation failure. Raw large-blob keys and low-level PRF results are wiped after conversion. No extension result is added to account labels or diagnostic reports by this provider package.

## References

- [W3C WebAuthn Level 3 extension definitions and PRF test vectors](https://www.w3.org/TR/webauthn-3/#sctn-extensions)
- [W3C WebAuthn Level 2 UVM definition](https://www.w3.org/TR/webauthn-2/#sctn-uvm-extension)
- [FIDO CTAP 2.1 extension definitions](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-20210615.html#sctn-extensions)
- [Android credential-provider integration](https://developer.android.com/identity/sign-in/credential-provider)

## Local verification

The four repository targets passed under JDK 17: `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `assembleDebugAndroidTest`. The final forced rebuild used `--offline --no-watch-fs --rerun-tasks --console=plain` and took 313.45 seconds, including daemon startup; 192 unit tests passed with no failures, errors, or skips. Ten provider extension tests cover W3C salt/ciphertext vectors, both PIN protocols, mandatory PRF user verification, credential-specific input precedence, capability fallback, malformed extension output, multiple accounts, encrypted blob round trips, and unknown-write non-replay.

The published diagnostic verification fix `46e01273810aa5366488fd35b8bf25f9e20be911` is included by normal merge. Its six regression tests pass. A prior incremental run reused stale manager classes after that merge and failed four negative tests; the fresh compile resolved this without source or test changes.

Android-test APK compilation is not an emulator/device test run. Physical transport, browser/native RP interoperability, and account acceptance remain separate qualification steps.
