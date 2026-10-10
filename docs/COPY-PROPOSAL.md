# Copy approval dependency

This is an internal review list. The following meanings need exact Chinese runtime wording approved by the Owner. They are not activation instructions. Plain labels already approved by the Owner are used directly.

| Key | Required meaning |
| --- | --- |
| operation.completed | The requested operation completed. |
| operation.notDispatched | The command was not sent to the card. |
| operation.unknown | The command was sent but its response was lost. The change may have taken effect. Inspect the card before another attempt. |
| operation.appliedReadbackFailed | The card accepted the change, but readback did not finish. Do not repeat the change. |
| error.contactRequired | This PIV operation requires a contact reader. NFC permits public reads only. |
| error.physicalActionRequired | Remove and reinsert the card, or move it away from NFC and tap it again. The app will not reset the card to substitute for physical presence. |
| error.pinInvalid | PIN or PUK verification failed. Remaining retries may have decreased. |
| error.pinBlocked | The PIN or PUK is blocked. |
| error.unsupported | This card or reader does not support the operation. |
| error.authorization | The card did not authorize this operation. |
| error.malformed | The card response was invalid. |
| error.keyMismatch | The certificate public key does not match the selected card slot. Nothing was imported. |
| error.invalidInput | Check the required fields and their formats. |
| error.transport | The card connection failed. |
| error.cancelled | The operation was cancelled. Cancellation does not undo a command already sent. |
| reader.nfc | Select an operation, then hold the card near the phone. |
| reader.usbPermission | Allow CardKey to access this USB reader in the system dialog. |
| reader.empty | No supported USB reader is connected. |
| confirm.pinChange | Incorrect current PIN/PUK input consumes a retry. Continue with this change? |
| confirm.adminChange | Replacing the management key changes future administration access. Retain the new key securely before continuing. |
| confirm.credentialDelete | Delete the selected credential? It cannot be restored by this app. |
| confirm.credentialUpdate | Update the selected credential's user information? Its user handle stays unchanged. |
| confirm.alwaysUv | Change whether FIDO operations always require user verification? Enabling it can disable U2F use. |
| confirm.minPin | Apply the minimum PIN length and optional forced PIN change? The minimum cannot be lowered on this card. |
| confirm.enterprise | Enable enterprise attestation using the card's provisioned policy? Attestation can identify this authenticator to an allowed relying party. |
| confirm.keyGenerate | Generate a new key in the selected PIV slot? Any existing private key in that slot will be lost; its certificate may no longer match. |
| confirm.certificateImport | Replace the selected slot's certificate after verifying its public key matches the card? |
| confirm.registration | Create a diagnostic credential for cardkey.example? It uses card storage when resident credentials are enabled. |
| confirm.pivReset | Reset PIV only if both PIN and PUK are already blocked? Blocking both has already erased the keys; reset clears PIV data and restores its initial credentials. FIDO is unaffected. |
| restriction.fidoReset | FIDO factory reset is unavailable. Reinstalling the FIDO applet is a separate desktop provisioning operation that erases FIDO credentials. |
| status.verification | Signature, CSR, decryption and ECDH checks verify the returned cryptographic result; they do not establish CA trust or acceptance by an external account. |

Pending confirmation text must not be placed in runtime resources until approved. Services and synthetic tests continue independently. Dangerous UI actions remain unavailable until their confirmation text is approved and wired. Reader, protocol, UI, physical-device acceptance and external-account acceptance are separate evidence.
