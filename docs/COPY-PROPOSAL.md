# Approved UI copy scope

The Owner approved the revised Chinese product wording and activation of the corresponding operations on 2026-10-10. The Android strings are in `app/src/main/res/values/strings.xml`. Only approved product strings were transferred; no private planning document, applet source or private dependency is part of this repository.

The Android resource set contains 34 applicable strings from the approved 36-key batch. Desktop FIDO applet-reinstallation confirmation and the iPhone NFC permission message do not apply to this Android management package and are not included.

Confirmations distinguish PIN/PUK retry risk, irreversible credential deletion, slot-key replacement, certificate replacement, management-key rotation, authenticator configuration and the already-blocked PIV reset requirement. Live PIV retry facts determine the additional final-attempt warning; its placeholders accept only PIN and PUK.

Results distinguish a command that was not dispatched, an unavailable result, an accepted write with failed readback, explicit protocol rejection and completion. Retry-counter changes do not by themselves establish that the requested management write was accepted.

The application displays the verification scope alongside cryptographic diagnostic results. FIDO applet reinstallation remains a separate desktop provisioning operation; the Android app exposes no CTAP reset control.

On 2026-10-11, the Owner approved and activated six additional Android strings: NFC unavailable/disabled, capacity exceeded, recovery required, card rejected, and raw-key Large Blob overwrite confirmation. Every raw-key blob write requires confirmation, including writes without a PIN. Recovery wording covers both unmet operation prerequisites and mismatched readback. The raw-key warning applies only to this manager flow; it does not replace the separate credential-bound Large Blob recovery warning in descendant work.

On 2026-10-11, the Owner approved the Android UV retry row: label `UV 剩余重试次数`, value `N 次`. It appears only when the authenticator returns a UV retry count; an absent value remains absent, while a returned zero is displayed as `0 次`. This wording does not change UV capability or retry behavior.

On 2026-10-14, the Owner approved the PIV management wording batch. Slot and generated-key results display `PIN 验证策略` and `触摸确认策略` from verified metadata, including valid empty slots. PIN policy values 1–3 display `无需 PIN`, `会话内验证一次`, and `每次操作验证`; touch values 1–3 display `无需触摸`, `每次操作触摸`, and `允许缓存触摸确认`. Absent metadata does not display invented policies. These rows report card responses and do not establish physical touch support or a cache lifetime.

The same approved batch activates Android file signing: the operation is `文件签名`, the picker is `选择文件`, and its limit is `选择非空文件，最大 1 MiB。`. The selected filename and exact byte count identify the input. `签名验证：通过` appears only after independent verification, followed by the approved explanation that the export is a detached signature and the source file is unchanged. Export reports `已导出签名。` only after write and close succeed, `已取消导出。` for picker cancellation, and `未确认导出结果。请检查所选文件。` for an unconfirmed export. These statements do not claim remote synchronization, certificate trust, physical qualification or company-system integration. [File-signing scope](PIV-FILE-SIGNING.md)
