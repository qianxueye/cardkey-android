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
