# PIV client certificate enrollment

The PIV／PKI panel requests a certificate from a selected step-ca endpoint and imports it only after explicit confirmation. This application workflow is implemented and tested with a disposable loopback CA and a synthetic APDU card. It does not establish a deployed CA, physical-card qualification, Android KeyChain integration, or acceptance by VPN, SSH, email, or other system consumers.

## Use the enrollment panel

Select a current USB APDU reader. Enter the CA's HTTPS origin and the common name (CN), then choose a CA root certificate through the system document picker. The selected root applies only to this workflow; it is never installed in the system trust store.

The card must already have a P-256 key in PIV slot 9A. Generate the CSR using the PIN, export it, and obtain a CSR-bound one-time authorization token from the CA administrator. The application does not generate a key automatically and contains no CA provisioner private key.

Submit the token once. A verified result means the application checked the returned certificate; it has not yet written the card. Confirm import separately and provide the management key. The application opens a new card session, reads the current slot public key before management authentication, writes only the leaf certificate, and requires exact certificate readback. The intermediate certificates remain available in the application and PEM export.

A lost or cancelled issuance response remains unknown. Do not resubmit that CSR; first check with the CA administrator. Issuance history is separate from the existing card-write recovery fence. Rejected and invalid certificates are not imported. Unknown or accepted-but-unread card writes retain the manager's existing recovery rules.

## Certificate and transport contract

- Exactly one CN; no other subject attributes and no subject alternative names.
- P-256 public key matching the independently verified, card-signed CSR; `digitalSignature` only, `clientAuth` only, and `CA=false`.
- Maximum 24-hour remaining validity at verification, with at most five minutes of backdating. Import rechecks current validity against the original verification time.
- A fixed chain of at most five certificates, each at most 16,384 DER bytes. Java PKIX verifies the chain against the selected root. Revocation fetching and AIA path discovery are not used.
- HTTPS origin only: no URL credentials, path prefix, query or fragment. The sign endpoint is `/1.0/sign`, with the selected root and ordinary hostname verification. Redirects, automatic connection retries, cookies, authenticators and cache are disabled.
- At most 131,072 response bytes. A known response length must match the received byte count before parsing or certificate validation; a mismatch leaves issuance unknown. Token preflight checks subject, audience, times, a lifetime of at most 600 seconds, and `cnf["x5rt#S256"]` containing the unpadded base64url SHA-256 digest of the full CSR DER. The CA verifies the token signature and authorization.

The reader route identifies an attachment, not a physical card. The public-key comparison prevents import into a replacement card with a different key; it does not prove physical identity or hardware key provenance.

## Lifecycle and secrets

The card session and operation gate close before HTTP starts. PIN and management-key buffers are cleared, and secret form fields are cleared before submission. Ordinary document-picker pauses preserve settled public CSR and certificate artifacts in the ViewModel. Pauses cancel active work, clear pending confirmations and invalidate callback ownership. A cancelled issuance retains an unknown receipt and its immutable public CSR. Attempted CSR bindings remain blocked for the process lifetime, including when a card regenerates identical CSR bytes after a selection change.

Changing the CN, CA, root or reader, observed detach, and explicit abandonment retire import and issuance eligibility. Returning to an earlier selection does not restore it. Root-picker results also belong to their captured request and settings generation. Public artifacts are not persisted across process death.

## Run the contract tests

Install the official Smallstep CLI **0.31.0** and step-ca **0.30.2**, then set `CARDKEY_STEP_CLI` and `CARDKEY_STEP_CA` to their executable paths. The repository's public CI installs those exact releases. The real-CA test fails when either executable is unavailable or has the wrong version; it does not silently skip.

Run the normal Android gate:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

The fixture creates its own CA in a private temporary directory, obtains an online CSR-bound token, and runs the application's actual CSR, HTTPS, PKIX and import/readback paths. It suppresses private process output, terminates its own CA process and removes its temporary keys and database. It needs no private repository, real credentials, connected reader, physical card, or system trust change.

The suite also tests hostname/root rejection, redirect and retry refusal, bounded responses, certificate profile and path failures, token binding, replacement-card rejection before authentication, wrong-PIN diagnostics, last-attempt confirmation, cancellation, selection changes, export pauses and uncertain card effects.
