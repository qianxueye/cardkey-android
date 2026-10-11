# CardKey CS7 wire contract v1

This contract defines the approved CS7-only, pairing-required test profile. It is an implementation contract, not a claim of PIV/FIPS certification or physical NFC acceptance.

## Sources and scope

The authority is the final July 2024 [SP 800-73 Part 2](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-73pt2-5.pdf), §§3.2.1, 4.1–4.3; [Part 1](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-73pt1-5.pdf), §§3.3.2, 3.3.7–8, 5.1.3, 5.5; and [SP 800-78-5](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-78-5.pdf), §§3.1 and 6. No draft OpenFIPS behavior defines this wire format.

CardKey intentionally permits plain NFC reads of public certificates and basic metadata. This differs from the NIST contact-restricted certificate access policy. PIN and private-key operations require the current command to be protected and pairing to have succeeded in its current session. Administration, issuance, PUK recovery, reset, key generation/import and management-key authentication remain physical-contact-only, including after pairing. The test profile reserves one PIN attempt for contact recovery: at one remaining attempt, protected NFC VERIFY returns authenticated `6983` without comparing the supplied PIN or decrementing the counter. Production issuer policy remains a separate Owner acceptance item.

## Establishment and trusted identity

Only key `04`, GA algorithm `2E`, P-384 ECDH, SHA-384 KDF and AES-256 are supported. Before any pairing code or PIN is sent, consumers must validate the CICC signature against an explicitly enrolled trusted issuer key or validated certificate, its P-384 curve, role `00`, GUID and card public key identity. Neither returned object `5FC122` nor an eight-byte issuer identifier creates trust.

Host: `00 87 2E 04 Lc [7C L [81 6A [00 || IDsH(8) || 04 || X(48) || Y(48)] 82 00]] 00`.

Card: `7C L [82 L [00 || NICC(24) || AuthCryptogramICC(16) || CICC]]`, followed by outer `9000` or transport `61xx`.

`IDsICC = SHA256(CICC)[0:8]`, even for CS7. The hash covers the complete CICC including its `7F21` tag and length.

The KDF concatenates `SHA384(counter32be || Z(48) || OtherInfo)` for counters 1, 2 and 3 and truncates to 128 bytes. Split the result into four consecutive 32-byte keys in this order: `SKCFRM`, `SKMAC`, `SKENC`, `SKRMAC`.

`OtherInfo = 04 0D 0D 0D 0D 08 || IDsH || 01 || 00 || 10 || Xhost[0:16] || 08 || IDsICC || 18 || NICC || 01 || 00`.

`AuthCryptogramICC = AES-CMAC(SKCFRM, ASCII("KC_1_V") || IDsICC || IDsH || Xhost || Yhost)`; transmit all 16 bytes. The point-format byte `04` is omitted from both KDF coordinate input and confirmation input. Destroy the shared secret, ephemeral host private key and confirmation key after use. A new or failed establishment destroys any previous session. No persistent binding is supported.

## CICC

Use canonical lengths, exact field order and one of each field:

`7F21 L { 5F29 01 80 | 42 08 issuerID | 5F20 10 GUID | 7F49 6A {06 05 2B81040022 | 86 61 [04 X Y]} | 5F4C 01 00 | 5F37 L DigitalSignature }`.

The signature covers the exact five TLV fields preceding `5F37`. `issuerID` is the first eight bytes of the issuer certificate's Subject Key Identifier. `DigitalSignature` is DER `SEQUENCE { AlgorithmIdentifier, BIT STRING }`; AlgorithmIdentifier is `30 0A 06 08 2A8648CE3D040303` with absent parameters. The BIT STRING has zero unused bits and contains DER `SEQUENCE { INTEGER r, INTEGER s }`. Raw `r || s`, bare ECDSA DER, NULL algorithm parameters, trailing data, wrong role, alternate curve OID or mismatched GUID/public key are rejected.

## Protected logical messages

Protect the complete message before fragmenting. A logical command uses canonical header `0C INS P1 P2`; non-final fragments use CLA `1C` and the final fragment uses `0C`. Lc is not MACed. Each non-final fragment receives only transport `9000`, which must not be treated as PIN or user-presence success.

Command body: optional `87 L [01 || ciphertext]`, optional `97 01 00`, then mandatory `8E 08 cmac[0:8]`. A data-free VERIFY omits `87`. GET DATA and GENERAL AUTHENTICATE require `97 01 00`. Response body: optional `87 L [01 || ciphertext]`, mandatory `99 02 innerSW`, then mandatory `8E 08 rmac[0:8]`. Even status-only handler errors have authenticated `99` and `8E`.

Both encryption directions use AES-CBC and ISO 7816 padding (`80` followed by zeroes, always 1–16 bytes). The 16-byte big-endian encryption counter starts at one. Command IV is AES-ECB(SKENC, counter). Response IV uses that same counter with its first byte replaced by `80`. Increment once after each complete logical protected exchange. End the session before the counter enters its first byte, whose replacement by `80` would otherwise repeat a response IV. Fragment acknowledgments and GET RESPONSE do not increment it.

`fullCmac = CMAC(SKMAC, previousFullCmac || 0C INS P1 P2 80 [00 × 11] || encoded87? || encoded97?)`.

`fullRmac = CMAC(SKRMAC, previousFullRmac || encoded87? || 99 02 innerSW)`.

Command and response chains each start with sixteen zero bytes and retain all sixteen MAC bytes independently; only the first eight bytes are transmitted. MAC input receives no additional application-level padding beyond the encoded header; CMAC applies its own final-block rules.

Collect ciphertext response fragments using plain `00 C0 00 00 Le`. The host must finish response assembly, validate outer status, canonical object order and the complete R-MAC, then decrypt/unpad and expose `innerSW` or plaintext. An outer failure can never become inner success. Do not send another logical command while a response is pending.

Bind a command chain to its header, interface and session generation. Changed headers/CLA/interface, overflow, omitted/reordered/replayed fragments and unrelated commands while output is pending invalidate the session. Deselect, reselect, reset and interface changes also invalidate it. Failure before handler dispatch cannot affect PIN/PUK retry counters. Missing SM objects use outer `6987`; malformed objects, bad MAC or padding use outer `6988`; absent session keys use outer `6982`. An SM error destroys session keys.

Pairing is protected `VERIFY 00 98` carrying eight ASCII digits. Success returns authenticated `9000`; a wrong pairing value returns authenticated `6300`, does not consume PIN/PUK tries, and has no pairing retry counter. Discovery `5F2F=48 00` and SELECT algorithm `2E` appear only after the complete profile is active.

## Public fixture provenance

`../fixtures/cs7-v1.json` is an explicitly published synthetic interoperability fixture generated with independent Python cryptography from the final NIST standards. Its private scalars 3, 5 and 7, GUID, pairing digits, PIN example and nonce are deliberately public test values. Never provision them. It includes CICC, ECDH, KDF, confirmation, IV and MAC-chain values and four protected exchanges. No card readback, real credential, issuer secret or private implementation source is included. The fixture is distributed under this repository's Apache-2.0 license. Public Android builds and tests depend only on these local fixtures, the primary standards and public libraries.

## Android protocol API and lifetime

`EnrolledPivCard(issuerSpki, issuerId, guid, cardPoint)` requires previously trusted issuance data. It copies the input identity bytes and accepts only a P-384 issuer key and a valid P-384 card point. The caller must obtain the issuer SPKI and the GUID/key binding from a trusted enrollment path. Passing an arbitrary card-returned key to this constructor does not establish trust. This package does not add enrollment storage or an enrollment screen.

Within one `CardSession`, call `PivSecureContext.establish(session, identity, pairing)` and construct `PivClient(session, context)`. Establishment selects PIV, verifies CICC and key confirmation, then sends the encrypted eight-digit pairing code. The code is cleared on success or failure. The context is returned only after an authenticated pairing success. PIN verification and private operations remain separate steps; pairing never reports PIN verification or FIDO UV.

The protected client supports existing signature checks, ECDH, RSA decryption and card-signed CSR creation. Public metadata/certificate responses pass through secure-message verification before the existing PIV parser. `pinStatus()` sends a data-free VERIFY and returns `PivPinStatus(verified, retriesRemaining, contactRequired)`. A verified response does not invent a retry count. An authenticated `6983` does not invent a count either; it requests a transition to contact recovery only when the current session uses NFC. An already-contact session does not request that transition. Ordinary VERIFY preserves the card's authenticated status and records a retry-state change only for `63Cx`.

Administration keeps its physical-contact guard before APDU transmission. The protected exchange path allows only PIN VERIFY, slot private-key operations and public reads. It cannot send PUK recovery, management-key authentication, key generation/import, certificate writes or reset. Unknown transport is rejected. The existing manager screens continue to construct the ordinary client; using this API from the manager requires a separate approved enrollment and consumer workflow.

`CardSession` serializes each logical exchange. A plain APDU, reselect, new establishment, close, protocol failure or observed interface change invalidates the secure context. A caller that observes reset, deselect, reconnection or cancellation must close/invalidate the session before further use. Session close clears its context automatically. No binding or session keys are stored for reconnection. The operation gate continues to own transport cancellation and resource closure.

The implementation clears its shared-secret, derived-key and PIN/pairing byte arrays, clears retained keys/chains/counter on close, and requests destruction of the JCA ephemeral private key. JVM/JCA providers can retain internal copies and may not support private-key destruction; this is best-effort memory cleanup, not a claim of guaranteed physical memory erasure. No secret material is logged.

Protected fragment acknowledgments and outer statuses never mark a write accepted. The complete response must pass canonical TLV, R-MAC, decryption and padding checks before its inner status reaches `PivClient`. A lost or invalid response after a protected mutation was dispatched produces `Effect.UNKNOWN` and destroys the context; no automatic replay occurs. Previously recorded accepted writes retain their independent `writeAccepted` evidence.

## Software verification scope

The checked-in fixture verifies CICC signature and identity binding, ECDH, the complete KDF, key confirmation, command/response IVs, sequential independent MAC chains and four envelopes, including a 256-byte response and an RSA-sized command. Kotlin computes these values independently through JCA and the existing public Bouncy Castle dependency.

Scripted protocol tests cover issuer/GUID/card-key rejection before pairing, role/OID/DER rejection, failed confirmation/pairing, authenticated PIN status and errors, retry-floor status, malformed/reordered/duplicated/truncated objects, MAC/padding/replay failures, counter exhaustion, pending responses, 255/256-byte logical bodies, plain GET RESPONSE, uncertain mutations, prior accepted-write evidence and context invalidation. P-256/P-384/RSA signatures, CSR, ECDH and RSA decryption run through protected exchanges. All existing FIDO and ordinary contact PIV tests remain in the normal gate.

These are local synthetic software tests. Physical Android NFC/USB, applet interoperability, enrolled issuer distribution and actual PIN recovery remain separate acceptance work. See [validation results](VALIDATION.md).
