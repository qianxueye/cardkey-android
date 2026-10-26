# Card artwork integration

The manager accepts an optional CardArtworkReference and CardArtworkSource. The owning application supplies the public record identifier and optional revision. Neither ATR, AAGUID, reader name nor credential identity is a lookup key. MainActivity keeps the physical-card home on its unconfigured illustration and supplies `IssuerBadgeRoute` for the separate 工卡记录 entry. That route uses authenticated issuer metadata and images; it never infers an employee from ATR, AAGUID, reader or credential identity.

The issuer adapter implements the suspend load method and returns encoded PNG/JPEG bytes plus a concise accessibility label, or null when artwork is absent. The manager already owns loading, display, failure and image-only retry. Artwork loading is independent of card commands and never retries a mutation.

Payload limits: 4 MiB encoded, width and height 1–4096, at most 16,000,000 source pixels, nonblank label of at most 200 Unicode scalars. BitmapFactory first checks metadata, then decodes a sampled preview no larger than 1200 pixels on its longest edge. The view fits the image without distortion. Unsupported/malformed data produces a visible artwork failure.

A source timeout or cancellation of the current fetch exposes image-only retry while the owning scope remains active; cancellation is rethrown to preserve coroutine ownership. Changing record, revision or selected reader context cancels/retires the former request. Clearing the reference or disposing the manager clears the former artwork. The generic CardKey illustration is labeled as a representation.

Synthetic tests cover PNG/JPEG, absent source, invalid/oversize/unsupported data, preview sampling, failed-source retry, changed revision, cancelled stale work and clearing. Rendering fixtures use disconnected state and do not instantiate MainActivity, enumerate readers or invoke card operations.


## Issuer record retrieval

`IssuerReader` opens `/v1/me`, then bounded pages of `/v1/people` and `/v1/cards?person_id=…`. Explicit person/card selection reads `badge-current` and `badge-revisions`; each selected immutable revision supplies front/back through the existing `CardArtworkLoader`. Metadata is checked for the selected person/card association. Image paths are derived from validated revision UUIDs, never response URLs. PNG/JPEG content type, descriptor length and dimensions are checked before the existing decoder. The issuer adapter limits each downloaded side to 1 MiB and 1200 pixels per dimension; JSON responses are capped at 4 MiB. Redirects and automatic transport retries are disabled.

The configurable issuer origin accepts HTTPS origins only. It is independent of the CA address and one-time signing tokens. The operator token is encrypted with AES-GCM under AndroidKeyStore; authenticated additional data binds each ciphertext to its normalized origin. Only the last successful origin is stored as public preferences. Android backup and extraction exclusions already apply to this application. A blank token reuses only that origin's saved credential after an explicit connection action.

Endpoint/operator/person/card/revision changes retire pending jobs and clear prior images before starting another load. Metadata awaits check cancellation before committing state. Artwork references include endpoint, operator, card and revision. A 401 retires the session and removes the saved credential only when it matches the token used by the rejected request. An independently replaced saved token survives a delayed rejection. Connection edits/disposal clear transient records. Existing CA unknown-result receipts and physical card workflows are not mutated by this reader.

The HTTP/Compose fixtures use only synthetic employees, identifiers, operator tokens and portrait PNGs over local loopback. The AndroidKeyStore instrumentation test uses a fresh UUID test alias/preferences file, verifies rejected replacements preserve the saved token, and deletes only that alias and file. It does not launch MainActivity or access NFC/USB. The root owns any device execution.
