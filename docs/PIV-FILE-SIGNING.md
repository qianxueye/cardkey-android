# PIV file signing

The Android manager signs one selected file with the active key in the chosen PIV slot, independently verifies the signature and then offers `signature.bin` for export. The file is read as exact raw bytes, including files that look like PEM certificates. Input must contain 1 to 1,048,576 bytes.

| Active key | Hash | Export encoding |
| --- | --- | --- |
| P-256 | SHA-256 | DER ECDSA signature |
| P-384 | SHA-384 | DER ECDSA signature |
| RSA-2048 | SHA-256 | 256-byte RSA PKCS#1 v1.5 signature |

In the PIV screen, select **文件签名**, choose a slot and use **选择文件** to read a bounded snapshot through Android's document picker. Enter the PIN after returning from the picker. The app reads fresh algorithm and public-key metadata in the signing session before verifying the PIN. An empty slot or missing public key stops the operation; a certificate is not used as a fallback key.

Signing requires contact transport and the existing card-operation gate. The existing last-retry confirmation applies when one PIN attempt remains and the PUK is blocked, followed by a fresh retry-fact comparison. The app sends one signing operation and independently verifies its result against the same raw bytes and current public key. It never prehashes data before the existing PIV signing primitive hashes it.

The selected file and picker request belong to the current in-memory manager, reader and action. An intentional document-picker handoff or Activity recreation retains that owner. A normal pause, changed reader or action, replacement or cancellation retires the selection. Each file and save launch has a separate ephemeral ActivityResult registry key. Only keys with a retained ViewModel owner are registered again after recreation; callbacks are unregistered when delivered or when the Activity is destroyed. An old callback cannot consume a newer request after an A–B–A change, and callbacks after process death have no in-memory owner. PINs and file bytes are not written to SavedState or application storage.

At submission, the operation receives an independent snapshot and the selection is cleared. Cancellation can retire a queued input immediately; once execution claims it, only the worker wipes it after signing or verification terminates. This prevents a concurrent wipe from changing the signed bytes. Cancelled, malformed, mismatched or uncertain card responses produce no export. Card effect and dispatch evidence remain available; no signing attempt is repeated automatically.

Export captures one completed, verified signature before opening the save picker. A later result cannot replace that captured output. The app requests a truncating write and reports success only after both writing and closing the document-provider stream succeed. Cancelling the picker retains the verified result. A null stream, write failure or close failure leaves the export outcome unknown; the app neither signs again nor rewrites automatically. Export does not change the source file or create a signed PDF, email, CMS container or certificate bundle, and successful provider I/O does not establish remote synchronization.

JVM tests cover all three algorithms, exact raw input and size limits, active-key checks, independent verification failures, cancellation and buffer ownership, document callback lifetime, and captured export outcomes. The bounded `ScriptedPivFileSigningTest.rawFileSignaturesVerifyWithAndroidProvidersForAllThreeAlgorithms` instrumentation test uses synthetic keys, a scripted card channel and synthetic input without opening an Activity or accessing a reader. Its execution is separate from build results and does not qualify a physical card, reader, PIN or external consumer.
