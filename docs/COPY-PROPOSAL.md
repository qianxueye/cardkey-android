# Approved UI copy scope

The Owner approved the revised Chinese product wording and activation of the corresponding operations on 2026-10-10. The Android strings are in `app/src/main/res/values/strings.xml`. Only approved product strings were transferred; no private planning document, applet source or private dependency is part of this repository.

The Android resource set contains 34 applicable strings from the approved 36-key batch. Desktop FIDO applet-reinstallation confirmation and the iPhone NFC permission message do not apply to this Android management package and are not included.

Confirmations distinguish PIN/PUK retry risk, irreversible credential deletion, slot-key replacement, certificate replacement, management-key rotation, authenticator configuration and the already-blocked PIV reset requirement. Live PIV retry facts determine the additional final-attempt warning; its placeholders accept only PIN and PUK.

Results distinguish a command that was not dispatched, an unavailable result, an accepted write with failed readback, explicit protocol rejection and completion. Retry-counter changes do not by themselves establish that the requested management write was accepted.

The application displays the verification scope alongside cryptographic diagnostic results. FIDO applet reinstallation remains a separate desktop provisioning operation; the Android app exposes no CTAP reset control.
