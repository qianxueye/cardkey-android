# App-local PIV TLS qualification

The Android client can use the currently imported enrollment identity for one TLS 1.3 mutual-authentication request. The identity remains bound to its original CSR, explicitly selected client root, issuance verification time and current certificate validity. Only PIV slot 9A with P-256 and SHA-256 is supported.

After certificate import/readback, the PIV screen shows the TLS qualification panel. The user enters a separate server address, selects its root certificate and supplies a fresh PIN. Confirmation displays the fixed endpoint, certificate name and selected server root. Leaving the activity, changing the reader or cancelling retires the attempt; conflicting manager actions stay disabled until its cleanup completes. Opening the server-root document picker preserves the imported identity, while a later change of endpoint, identity or reader invalidates a pending file result.

The user selects an HTTPS endpoint and a separate server root. The endpoint requires an ASCII DNS name; the leaf must have an exact DNS SAN, serverAuth usage, digitalSignature key usage and a valid PKIX path to that root. Path validation uses only the supplied chain and anchor. There is no AIA fetching, common-name or wildcard fallback. The client sends SNI and offers HTTP/1.1.

A public card preflight checks the current certificate, SPKI and retry facts, then releases the reader before the network handshake. After validating the server, one external-signature callback acquires the operation gate, reconnects the currently selected USB route and checks those facts again before using the PIN. A changed route, certificate, public key or retry state stops the attempt. The existing last-retry confirmation is required when one PIN attempt remains and the PUK is blocked.

The TLS library hashes CertificateVerify once. The app sends the resulting 32-byte digest unchanged to PIV GENERAL AUTHENTICATE, then independently verifies the ECDSA signature. It does not export a private key, register a process-wide provider or install a system identity or trust anchor.

One attempt owns one socket, one TLS connection and one client-signature budget. Connection and read timeouts are 10 and 15 seconds, with a 45-second overall deadline that closes the socket and any active card channel and wipes the retained PIN. The client sends one HTTP GET and accepts only a bounded, bodyless HTTP/1.1 204 response followed by a clean TLS close. It does not follow redirects, use a proxy or connection pool, resume a TLS session, retry an HTTP request, or retry PIN verification or signing.

TLS outcome and card outcome are independent. A failed or cancelled network operation can still have changed PIN retry/security state or have an unavailable APDU response. The app retains the card effect and dispatch status so the user can inspect the card before deciding what to do next.

Synthetic tests use an owned loopback TLS 1.3 server that requires the client root and exact client leaf. They cover the real external-signature handshake, double hashing, absent/wrong credentials, server identity rejection before PIN, changed card facts, cancellation, lost responses and response bounds. These tests establish application and protocol behavior; physical USB/card and external-service acceptance require separate qualification.
