# PIV certificate enrollment

The PIV／PKI panel requests a certificate from a selected step-ca endpoint and imports it only after explicit confirmation. This application workflow is implemented and tested with a disposable loopback CA and a synthetic APDU card. It does not establish a deployed CA, physical-card qualification, Android KeyChain integration, or acceptance by VPN, SSH, email, or other system consumers.

## Use the enrollment panel

Select a current USB APDU reader. Enter the CA's HTTPS origin and the common name (CN), then choose a CA root certificate through the system document picker. The selected root applies only to this workflow; it is never installed in the system trust store.

Choose the certificate purpose. TLS client authentication requires an existing P-256 key in PIV slot 9A. Document signing defaults to 9C and accepts an existing P-256, P-384 or RSA-2048 key in the selected slot. The slot is a local import destination, not a CSR field or token-authorized claim. [Document profile and lifecycle](DOCUMENT-ENROLLMENT.md). Generate the CSR using the PIN, export it, and obtain a CSR-bound one-time authorization token from the CA administrator. The application does not generate a key automatically and contains no CA provisioner private key.

Submit the token once. A verified result means the application checked the returned certificate; it has not yet written the card. Confirm import separately and provide the management key. The application opens a new card session, reads the current slot public key before management authentication, writes only the leaf certificate, and requires exact certificate readback. The intermediate certificates remain available in the application and PEM export.

A lost response or cancellation after the CA call begins remains unknown. Do not resubmit that identity to the same CA; first check with the CA administrator. Cancellation before the queued call begins restores the unsubmitted CSR and does not spend the attempt. Issuance history is separate from the existing card-write recovery fence. Rejected and invalid certificates are not imported. Unknown or accepted-but-unread card writes retain the manager's existing recovery rules.

## Certificate and transport contract

- Exactly one CN; no other subject attributes and no subject alternative names.
- Public key matching the independently verified, card-signed CSR; `digitalSignature` only and `CA=false`. TLS requires P-256 and exactly `clientAuth`. Document signing accepts P-256, P-384 or RSA-2048 and requires exactly EKU `1.3.6.1.5.5.7.3.36`. A document identity cannot enter TLS qualification.
- Maximum 24-hour remaining validity at verification, with at most five minutes of backdating. Import rechecks current validity against the original verification time.
- A fixed chain of at most five certificates, each at most 16,384 DER bytes. Java PKIX verifies the chain against the selected root. Revocation fetching and AIA path discovery are not used.
- HTTPS origin only: no URL credentials, path prefix, query or fragment. The sign endpoint is `/1.0/sign`, with the selected root and ordinary hostname verification. Redirects, automatic connection retries, cookies, authenticators and cache are disabled. The request explicitly accepts identity encoding; a successful response with any other, empty, combined or repeated content coding leaves issuance unknown before parsing.
- At most 131,072 response bytes. A known response length must match the received byte count before parsing or certificate validation; a mismatch leaves issuance unknown. Token preflight checks subject, audience, times, a lifetime of at most 600 seconds, and `cnf["x5rt#S256"]` containing the unpadded base64url SHA-256 digest of the full CSR DER. The CA verifies the token signature and authorization.

The reader route identifies an attachment, not a physical card. The public-key comparison prevents import into a replacement card with a different key; it does not prove physical identity or hardware key provenance.

## Lifecycle and secrets

The card session and operation gate close before HTTP starts. PIN and management-key buffers are cleared, and secret form fields are cleared before submission. Ordinary document-picker pauses preserve settled public CSR and certificate artifacts in the ViewModel. Pauses cancel active work, clear pending confirmations and invalidate callback ownership. A cancelled begun issuance retains its unknown receipt, immutable CSR and CA/root context separately from the form. Attempted CSR bindings remain blocked during the ViewModel lifetime. An unknown-intent fence also blocks a randomized new CSR for the same purpose, slot, SPKI, CN and CA identity. Later requests do not overwrite unknown receipts; these records do not survive ViewModel destruction or app restart.

Changing the purpose, slot, algorithm, CN, CA, root or reader, observed detach, and explicit abandonment retire import and issuance eligibility. Returning to an earlier selection does not restore it. Root-picker results also belong to their captured request and settings generation. Public artifacts are not persisted across process death.

## Run the contract tests

Install the official Smallstep CLI **0.31.0** and step-ca **0.30.2**, then set `CARDKEY_STEP_CLI` and `CARDKEY_STEP_CA` to their executable paths. The repository's public CI installs those exact releases. The real-CA test fails when either executable is unavailable or has the wrong version; it does not silently skip.

Run the normal Android gate:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

The fixture creates its own CA with separate fixed TLS and document provisioners in a private temporary directory, obtains online CSR-bound tokens, and runs the application's actual CSR, HTTPS, PKIX and import/readback paths. Document qualification covers all three algorithms, wrong-role tokens and explicit use of the existing CMS consumer. It suppresses private process output, terminates its own CA process and removes its temporary keys and database. It needs no private repository, real credentials, connected reader, physical card, or system trust change.

The suite also tests hostname/root rejection, redirect and retry refusal, bounded responses, certificate profile and path failures, token binding, replacement-card rejection before authentication, wrong-PIN diagnostics, last-attempt confirmation, cancellation, selection changes, export pauses and uncertain card effects.
