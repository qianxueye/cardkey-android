# FIDO manager workspace copy

Owning issue: #45. Reviewed with plain-language-iso-24495 before application. Activation is the next manager build/install only. English/Chinese resources carry equivalent meaning. Provider, enrollment, protocol, credentials, PIN preparation/submission and runtime recognition policy are unchanged.

Current source permits secure PIN preparation before recognition; historical recognition-first documents are superseded by the actual delivered PIN-first source and tests. This package preserves that behavior. Selected transport is not identified card presence, and a prior information read is an observation, not current physical-card proof. Unknown or unread status never establishes that an applet is absent or initialization is needed.

| Screen / key | Before | Chinese after | English after | Reason / scope |
| --- | --- | --- | --- | --- |
| Manager route / `manager_fido_unread` | No explicit FIDO observation status | 尚未读取 FIDO 信息 | FIDO information not read | Describe missing observation without claiming absent applet, unsupported card or missing initialization. |
| Manager route / `manager_fido_observed` | Recognition record not summarized | 已读取 FIDO 信息 | FIDO information read | Use only the current route/lifecycle FIDO recognition fact; no continued-presence claim. |
| FIDO primary read / `manager_read_fido` | FIDO read buried under advanced settings, generic 读取 | 读取 FIDO 信息 | Read FIDO information | Dispatch the existing FIDO_INFO action explicitly; never combined PIV read. |
| FIDO task introduction / `manager_fido_intro` | Generic selected-object card and heading | 可先读取信息，或输入 FIDO PIN 查看通行密钥。 | Read information first, or enter the FIDO PIN to view passkeys. | Explain the optional public read and authenticated inventory while preserving PIN-first preparation. |
| Inventory submit / `manager_inventory_action` | 继续 | 查看通行密钥 | View passkeys | Name the actual authenticated action before submission; no change to PIN timing or full-inventory scope. |
| FIDO advanced entry / `manager_fido_advanced` | Only Settings has 高级 FIDO 设置与诊断 | 高级设置与诊断 | Advanced settings and diagnostics | Secondary entry from the FIDO task; existing advanced operation list and consent remain. |

Existing labels for passkeys, PIN setup/change, task selection, results, retry facts, unknown outcomes, key loss and export remain exact. No generic retry prompt is added. No initialization, provider activation or account-acceptance claim is introduced. The blue primary/yellow accent treatment is manager-scoped; original provider/enrollment theme consumers remain unchanged.

Current-source anchors at base `4ab71057`: `ManagerScreen.kt:146` uses only `!state.permissionPending` for secret preparation, and `:550-554` renders secure PIN fields under that condition. `PlexoWorkspaceTest.pinPreparationNeedsNoPriorReadButPermissionRetiresSecrets` proves preparation without a public read and retirement when permission changes. The real submit path remains `ManagerScreen.kt:330-360` → `ManagerViewModel.submit` → leased operation; preparation does not dispatch. New UI observation text uses the existing route-matched recognition fact as display only and cannot change PIN availability. Qualified Chinese/English catalogs grow from 137 to 143 keys for the six reviewed manager keys; the original 179 fallback exceptions are unchanged.

The landing introduction and public read control appear only for the unselected inventory/public-read task. PIN-change and other selected tasks prioritize their own secure fields rather than showing inventory guidance. The read control retains normal busy, permission and inspection guards; explicit primary-blue text improves its enabled-state contrast without forcing it enabled. The compact route header preserves the full reader product name as its own text node. Unknown/busy feedback remains owned by the existing operation result.

## Approved native home and inventory preparation

Issue #45. The Owner approved the four-platform concept, then clarified that only in-page graphics change. Launcher assets are unchanged. Reviewed before application with plain-language-iso-24495. Activation: next manager build/install. The new home is passive; opening preparation does not authenticate or dispatch. Existing public reading remains FIDO-only; PIV reading remains explicit in Certificates. These labels supersede the rejected inventory-first landing presentation above.

| Screen / key | Before | Chinese after | English after | Reason |
| --- | --- | --- | --- | --- |
| Home / `native_card` | No passive home | 卡片 | Card | Name the card workspace, without claiming card identity. |
| Home / `native_unread` | 尚未读取 FIDO 信息 | 尚未读取 | Not read | No observed application evidence; never absent applet. |
| Home / `native_observed` | 已读取 FIDO 信息 | 信息已读取 | Information read | Last matching route observation, not continued presence. |
| Application state / `native_app_unread` | No separate home state | 未读取 | Not read | FIDO and PIV are independently observed. |
| Application state / `native_app_read` | No separate home state | 已读取 | Read | Use only actual matching family/route evidence. |
| Primary read / `native_read` | 读取 FIDO 信息 | 读取卡片 | Read card | Existing explicit FIDO status operation; nearby scope names FIDO. |
| Read scope / `native_read_scope` | No home scope | 读取 FIDO 公开信息；证书信息请到“证书与签名”读取。 | Reads public FIDO information. Read certificate information in Certificates and signing. | Prevent a combined FIDO/PIV read claim. |
| NFC / `native_tap` | 选择操作后，将卡片贴近手机。 | 贴近手机的 NFC 感应区 | Hold the card near the phone’s NFC area | Contextual NFC route only. |
| USB / `native_usb` | No home instruction | 选择已连接的 USB 读卡器 | Select a connected USB reader | No NFC instruction on external-reader route. |
| Preparation / `native_prepare` | PIN field on initial screen | 准备 PIN | Prepare PIN | Opens local inventory preparation only. |
| Inventory title / `native_inventory_title` | 通行密钥 PIN in concept | 查看通行密钥 | View passkeys | Name the real inventory task, not an isolated PIN verification. |
| Inventory context / `native_target_unread` | No focused context | 目标卡片尚未识别 | Target card not identified | No recognition/identity fabricated by preparation. |
| Inventory context / `native_target_observed` | No focused context | 已在此连接读取 FIDO 信息 | FIDO information was read on this connection | Existing route evidence; no ongoing identity claim. |
| NFC preparation / `native_pin_tap` | General task guidance | 可先输入 PIN，再提交并贴卡。 | Enter the PIN first, then submit and present the card. | Preserve PIN-first preparation and explicit task submission. |
| USB preparation / `native_pin_usb` | General task guidance | 可先输入 PIN，再提交读取。 | Enter the PIN first, then submit the read. | Match USB transport; no tap requirement. |
| NFC submit / `native_submit_tap` | 查看通行密钥 | 提交并贴卡 | Submit and present card | Executes the existing complete inventory task; task title explains scope. |
| Certificates / `native_certificates` | 证书 | 证书与签名 | Certificates and signing | Existing PIV tasks, not new capabilities. |
| Secondary tasks / `native_technical` | 高级设置与诊断 | 技术信息 | Technical information | Existing advanced FIDO/PIV controls remain reachable. |
| Task navigation / `native_back` | Unlabelled arrow | 返回 | Back | Accessible native back action; clears local prepared secrets, never guarantees undo. |

Existing FIDO PIN field, cancellation, failure/unknown result, final-attempt warnings and all destructive/secret/export consequences remain exact. No placeholder PIN, example target, unprogrammed-card assertion or fake success enters runtime. Actual artwork loading, loaded images and retry retain their owners. English/Chinese catalogs include each new key with exact parity; no fallback exception is expanded.

## Home read cancellation visibility

PR #47 review correction: the home in-progress feedback now exposes the existing `取消` action, with the same meaning as Cancel elsewhere in the manager. Before: the pending home read had no cancel control. After: the unchanged cancellation label invokes existing `onCancel`; no new string/catalog key or cancellation guarantee is introduced. Activation is the next manager build/install. The actual task and NFC instruction stay beside it; known cancellation and unknown effects retain their original outcome policy. Artwork loading/failure width changes add no interface wording.

## CTAP PIN-required result guidance

Refs #45. Root reviewed the exact Chinese/English wording; plain-language review before application preserves the condition, uncertainty and actual activation timing. This is manager result presentation only, not PIN entry, verification, retry or a new authentication policy.

| Screen / key | Exact before | Chinese after | English after | Condition / reason / activation |
| --- | --- | --- | --- | --- |
| Manager operation failure / `error_pinRequired` | `error_authorization`: 卡片未授权此操作。 / The card did not authorize this operation. | 此操作需要 PIN 验证。 | This operation requires PIN verification. | Only existing AUTHORIZATION_DENIED with preserved CTAP status 0x36. Distinguish required verification from generic rejection. Next application build/install only. |

The public client preserves status 0x36 and currently resolves token acquisition through the PIN-based ClientPIN path. Existing built-in UV request flags remain unchanged; this condition does not redefine every verification path as PIN-only. Status 0x33, other authorization codes and status-less denial retain their exact generic message. Unknown, not-dispatched and accepted-write/readback warnings retain their original conditions and ordering. The text does not request or trigger automatic resubmission. No provider, protocol, consent, retry, effect or state behavior changes.
