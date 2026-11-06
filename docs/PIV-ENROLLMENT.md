# PIV certificate enrollment

The PIV／PKI panel selects a person and issued card from cardkey-issuer, requests a certificate from the selected CA, and imports it after explicit confirmation. Policy follows the issuer contract at `f65e5ad`, `docs/certificate-profile-v2.md`, and rulings `rul-20261103-b88d` and `rul-20261103-50c6`. Runtime and physical qualification remain separate coordinator gates.

## Use the enrollment panel

Select a USB APDU reader. Connect to the issuer with an operator account, select an active person and their issued card, and enter the configured CA identifier. Enter the CA HTTPS origin and choose its root certificate through the system document picker. The selected root is scoped to this workflow.

Choose a purpose and use the existing slot key to generate the CSR:

| Purpose | Slot | Key |
|---|---|---|
| Authentication | 9A | P-256 |
| Document signing | 9C | P-256, P-384 or RSA-2048 |
| Key management | 9D | RSA-2048 |

The person record supplies employee reference, display name, UPN and email. Authentication requires a UPN. The app freezes these values in the CSR request context; the CSR subject remains CN-only for the approved step-ca contract. Operators do not type the CN or paste a signing token. CSR export remains available.

Request the certificate once. The app first saves the request and stable IDs, registers the issuer intent, reads back and compares its complete identity and request fields, then calls `mint_issuance_signing_token`. It sends the returned token and CSR to `/1.0/sign`. The CA checks the token signature and authorization. The app validates the returned certificate against its frozen person identity and reports the result to the issuer.

Confirm import separately and provide the management key. A fresh card session compares the current slot public key before management authentication, writes the leaf certificate, and requires exact readback. The certificate chain remains available for PEM export. The reader route identifies an attachment; the key comparison does not establish hardware provenance.

## Certificate and transport contract

- The leaf has exactly four separate RDNs, in order: `C=US`, `O=Aperture Intelligence LLC`, `UID=employee_reference`, `CN=display_name`. Compare decoded values, accepting PrintableString and UTF8String.
- Authentication requires exactly digitalSignature KU, clientAuth and smartcardLogon EKUs, and one UTF8String UPN otherName (`1.3.6.1.4.1.311.20.2.3`). At most one matching email SAN is allowed.
- Document signing requires digitalSignature and contentCommitment KU, documentSigning and Microsoft Document Signing EKUs, plus emailProtection when email exists. SAN is exactly that email, or absent without email.
- RSA key management requires keyEncipherment KU. With email it requires emailProtection EKU and that email SAN; without email both are absent.
- The leaf key matches the independently verified card-signed CSR. basicConstraints may be absent or explicitly CA=false. Lifetime is at most 8760 hours plus 300 seconds, retaining issuance-window and current-validity checks.
- A fixed chain contains at most five certificates, each at most 16,384 DER bytes. Java PKIX verifies it against the selected root. Validation does not fetch revocation or AIA URLs.
- CA input is an HTTPS origin without URL credentials, path prefix, query or fragment. The sign endpoint uses the selected root and hostname verification. Redirects, retries, cookies, authenticators and caching are disabled.
- The CA response is bounded to 131,072 bytes. Content encoding and declared length are checked before certificate parsing. Token preflight checks CN, audience, times and the SHA-256 CSR binding; the token is not persisted.
- Issuer mutations use stable UUID idempotency keys, bounded responses and no automatic retry. Mint errors are isolated in `IssuerMintError`; no raw server message is displayed.

The X.509 structure and extension semantics follow RFC 5280 sections [4.1.2.6](https://www.rfc-editor.org/rfc/rfc5280#section-4.1.2.6), [4.2.1.3](https://www.rfc-editor.org/rfc/rfc5280#section-4.2.1.3), [4.2.1.6](https://www.rfc-editor.org/rfc/rfc5280#section-4.2.1.6), [4.2.1.9](https://www.rfc-editor.org/rfc/rfc5280#section-4.2.1.9), and [4.2.1.12](https://www.rfc-editor.org/rfc/rfc5280#section-4.2.1.12). Exact identity values and purpose sets come from the approved project profile.

## Recovery and lifecycle

Before issuer mutation, a checked synchronous preferences commit stores CSR DER, frozen identity, person/card/CA identifiers, root, purpose, slot, algorithm, and stable issuance and idempotency IDs. A failed commit stops preparation. PINs, operator credentials and minted signing tokens are excluded from this recovery state. Operator credentials retain the existing separate encrypted issuer store.

Cancellation before preparation leaves the CSR available. Once preparation completes, cancellation or missing responses preserve an unknown receipt and block another attempt. The fence also rejects a randomized CSR for the same unknown intent. Unknown receipts survive later requests and process recreation. Contact the issuing administrator using the displayed request ID before continuing an unknown request; no automatic reconciliation runs.

Validated bundles and pending result-report status survive process recreation. Historical bundles are checked at their recorded verification instant when loaded; import and TLS use recheck current validity. Initial reader selection retains a restored request. Subsequent reader changes, observed detach, explicit abandonment, or edits to the person, purpose, algorithm, CA or root retire current eligibility. Unknown receipts remain retained. IMPORTED recovery does not offer reimport.

A result-report failure preserves the local result and request ID and directs the operator to administrator reconciliation. Public-key checks and the existing card-write recovery fences still apply to every explicit import.

## Run the targeted checks

Use JDK 17 and Android SDK platform 36 with build tools 35.0.0. The contract tests require official Smallstep CLI **0.31.0** and step-ca **0.30.2**, selected through `CARDKEY_STEP_CLI` and `CARDKEY_STEP_CA`.

```sh
./gradlew :app:testDebugUnitTest --tests 'cc.cardkey.enrollment.*' --tests 'cc.cardkey.issuer.*'
```

The synthetic contract fixture creates a disposable CA with authentication, document-signing and key-management provisioners. It uses generated keys and synthetic card channels, suppresses private process output, and removes its own temporary state. It requires no real card, operator credential, deployed CA or system trust change.
