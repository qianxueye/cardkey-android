# Detached CMS document signatures

Refs [#35](https://github.com/qianxueye/cardkey/issues/35).

The PIV operation menu offers CMS file signing through the same bounded selected-file controller as raw signing. The CMS action selects the CMS format, shows its certificate requirements before signing, and reports signature verification, detached-file scope and trust limits after verification. The five approved UI strings are recorded in [the exact copy record](CMS-COPY-PROPOSAL.zh-CN.md). Raw file signing remains a separate action.

A successful CMS request produces one DER-encoded CMS `SignedData` file, `signature.p7s`, for a selected nonempty file of at most 1 MiB. The original content remains external. The container carries exactly one signer and that signer's certificate. It carries no document bytes, certificate chain, revocation evidence or timestamp. The existing raw `signature.bin` operation remains available separately.

The selected PIV slot must expose current algorithm and public-key metadata and a parseable X.509 end-entity certificate. Before PIN verification or a private-key operation, the certificate must be currently valid, include the `digitalSignature` key usage and explicitly include `id-kp-documentSigning` (`1.3.6.1.5.5.7.3.36`), and its SubjectPublicKeyInfo must match the slot metadata. A client-authentication-only certificate, a CA certificate, absent metadata or a missing public key cannot authorize CMS document signing. No certificate or algorithm fallback is used.

| Slot algorithm | CMS digest | Signature algorithm |
| --- | --- | --- |
| P-256 | SHA-256 | ECDSA |
| P-384 | SHA-384 | ECDSA |
| RSA-2048 | SHA-256 | RSA PKCS#1 v1.5 |

The app builds exactly two signed attributes: `contentType` (`id-data`) and `messageDigest` (the digest of the original bytes). Bouncy Castle supplies their DER `SET OF` encoding to the card-backed `ContentSigner`. The existing PIV message signer hashes those attribute bytes once and sends one `GENERAL AUTHENTICATE`. It does not sign the original document digest as if it were the CMS attribute digest. This follows RFC 5652 section 5.4; the certificate-purpose requirement follows RFC 9336.

Before making an export available, the app parses the emitted DER again, requires detached content, exactly one signer and the exact selected certificate, validates both attributes against the original bytes, and cryptographically verifies the signature. This is a signature/format check. It does not establish PKIX trust, revocation status, trusted signing time, identity acceptance or legal validity.

CMS uses the existing contact-only file picker, retained in-memory request ownership, PIN retry confirmation, card-operation gate and captured-result export lifecycle. Changing the reader or selected operation retires the selected file and pending callbacks. The request captures RAW or CMS with the selected-file snapshot. The successful report captures that format with its verified bytes; the save picker derives the filename and MIME type from that captured report, including across Activity recreation. Cancelling or losing a card response never repeats the signing operation. Writing and closing the output stream must both succeed before export is reported saved; signature verification and output-save status remain separate.

## Independent OpenSSL verification

Synthetic scripted-card tests write fixtures under `app/build/cms-openssl/P256`, `P384` and `RSA2048`. Run with OpenSSL 3:

```sh
openssl cms -verify -binary -inform DER -in signature.p7s \
  -content original.bin -nointern -certfile signer.pem -noverify -out verified.bin
cmp original.bin verified.bin
```

`-nointern` selects the supplied exact signer certificate instead of discovering it from the container. `-noverify` skips certificate-chain trust evaluation; signature verification remains enabled. These commands prove interoperability of the container and signature, not certificate trust. OpenSSL's default S/MIME certificate-purpose evaluation is not the application's document-signing profile.

All fixtures use generated test keys and synthetic certificates. No physical card, reader, PIN, external identity, production deployment or company-product integration is qualified by these tests.
