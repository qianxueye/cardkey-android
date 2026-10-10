# Repository instructions

Implementation status: the Owner approved the Android manager package and public repository. Sensitive frontend confirmation wording remains a separate approval; protocol implementation and testing continue independently. Android Credential Provider integration is a later independent package.

Use English for subagent briefs and internal handoffs, and Chinese for Owner-facing explanations. Follow the parent workspace Git, signing, attribution, review, verification and copy rules. Preserve unrelated work. After bootstrap, use a fresh task branch from the current remote default branch, commit coherent steps, and publish one consolidated tested candidate per package or review round.

Verify live issue/PR state before implementation. Keep each package independently working and reviewable, with its necessary tests and documentation. Root owns remote gates and closeout; one assigned owner serializes all physical card mutations and integration sessions. No agent should wait on CI in a self-spawned background loop.

Preserve the Owner-confirmed J3R180 / Java Card 3.0.5 / GP 2.3 baseline. Keep FIDO, PIV, reader, app and system-consumer acceptance separate. Do not claim deployment, certification, account acceptance or successful writes without the corresponding readback. Do not automatically retry uncertain mutations.

Keep management keys, PINs, attestation private keys, signing keys, real credentials and raw private logs outside Git and CI output. Use synthetic fixtures. Never change repository visibility without the Owner-authorized publication scope. No routine checksum manifests or repeated reassurance tests.

Before adopting upstream source, pin the revision and preserve its license/notices. Frontend wording approval remains separate from functional approval.

Repository scope: Self-contained Kotlin/Compose application managing both FIDO2 and PIV through NFC/USB paths. Record physical qualification separately. Android 14+ credential-provider integration is outside this management package.
