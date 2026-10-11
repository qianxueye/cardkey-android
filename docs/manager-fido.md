# FIDO management workflows

The manager can replace the minimum-PIN disclosure RP list, request enterprise attestation mode 1 or 2, and select credential protection policies 1, 2 or 3. An empty disclosure field sends an empty list; it does not retain the previous list. The card advertises its list capacity and enforces authentication, minimum length and force-change policy. The client cannot read the stored disclosure list back.

The Owner approved the FIDO extension text and activation batch on 2026-10-10. Username updates omit the unsupported display-name field. Product display names come from `app_name`.

Secrets never enter ordinary result rows or automatic exports. Reading and viewing secret outputs use separate confirmations. Result buffers and the diagnostic session are cleared on cancellation, activity pause, reader change or reader removal. Ordinary operations clear the preceding secret output. Secret views use the existing secure-window policy.

Large Blob operations use a fresh assertion and the blob operation in the same `CardSession` and operation lease. The assertion addresses the previously verified diagnostic credential and its RP, requires UV, and requests `up:false`; it is a management identity proof rather than an RP login. The manager checks the signature with the cached credential public key, RP hash, credential ID and counter before using the fresh `largeBlobKey`. If an earlier key remains in the workflow, it must match the fresh key. An unsupported no-UP request, failed verification or missing key prevents the blob operation. No reconnect or automatic retry occurs between this proof and the blob operation.

Import plaintext before the verification workflow. Importing a document pauses the activity and clears earlier keys. Empty plaintext creates an encrypted empty entry; backend deletion passes `null` and removes the selected encrypted entry. Deletion is not exposed in the interface because a deletion consequence confirmation is outside the approved text batch.

Decrypted content export requires the approved confirmation and an explicit document destination. Only the authorized export buffer survives the document picker; it is cleared after writing, cancellation or activity destruction. The manager does not export `largeBlobKey` or hmac-secret output.

Local software tests and physical card/account qualification are separate. The management no-UP proof does not establish physical user-presence acceptance for RP login.
