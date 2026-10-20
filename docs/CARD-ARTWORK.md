# Card artwork integration

The manager accepts an optional CardArtworkReference and CardArtworkSource. The owning application supplies the public record identifier and optional revision. Neither ATR, AAGUID, reader name nor credential identity is a lookup key. MainActivity currently uses the unconfigured default: no network, database, private dependency or identity inference.

A future adapter implements the suspend load method and returns encoded PNG/JPEG bytes plus a concise accessibility label, or null when artwork is absent. The manager already owns loading, display, failure and image-only retry. Artwork loading is independent of card commands and never retries a mutation.

Payload limits: 4 MiB encoded, width and height 1–4096, at most 16,000,000 source pixels, nonblank label of at most 200 characters. BitmapFactory first checks metadata, then decodes a sampled preview no larger than 1200 pixels on its longest edge. The view fits the image without distortion. Unsupported/malformed data produces a visible artwork failure.

A source timeout or cancellation of the current fetch exposes image-only retry while the owning scope remains active; cancellation is rethrown to preserve coroutine ownership. Changing record, revision or selected reader context cancels/retires the former request. Clearing the reference or disposing the manager clears the former artwork. The generic CardKey illustration is labeled as a representation.

Synthetic tests cover PNG/JPEG, absent source, invalid/oversize/unsupported data, preview sampling, failed-source retry, changed revision, cancelled stale work and clearing. Rendering fixtures use disconnected state and do not instantiate MainActivity, enumerate readers or invoke card operations.
