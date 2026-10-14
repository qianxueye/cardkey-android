# Plexo Android workspace copy

The approved native workspace package activates these strings in the manager only. The plain-language review preserves explicit selection versus execution, unread versus observed empty, current selection versus captured result, and existing irreversible/unknown-effect warnings. No new card/network operation is introduced. Existing confirmation, retry, capability, file, cryptographic scope and recovery strings retain their meaning and execution guards. Root records this checkpoint in issue 35.

| Screen / key | Before | Applied wording | Reason / activation |
| --- | --- | --- | --- |
| Device / workspace_disconnected | Generic reader heading with unread state | 当前连接 · 未选择 USB 读卡器 | Describe the actual absent selected reader; manager header only. |
| Object navigation / workspace_objects | Flat task form | 对象列表 | Native list/detail control and native Back destination. |
| Detail / workspace_selectedObject | Object fields mixed into task form | 当前选择 | Distinguish browsed target from captured report. |
| Detail / workspace_selectedSlot | 槽位 dropdown | 槽位 · %1$s | Actual selected catalog slot; no presence claim. |
| List and task picker / workspace_selectionOnly | No selection explanation | 选择对象或任务不会发送卡片指令。 | Browsing changes only the existing scene/selection callbacks. |
| FIDO list / workspace_authenticator | FIDO2 heading | 认证器 | Opens the existing information/security task context. |
| PIV list / workspace_pivSecurity | PIV heading | PIV 状态与安全设置 | Card-wide tasks retain a separate target from slot tasks. |
| PIV list / workspace_slotCatalog | 槽位 dropdown | 槽位目录 | Catalog entries are valid supported slot identifiers, not observations. |
| PIV catalog / workspace_slotAuthentication | Slot ID only | 身份验证 | 9A catalog role, not claimed authentication. |
| PIV catalog / workspace_slotSignature | Slot ID only | 数字签名 | 9C catalog role, not claimed signature. |
| PIV catalog / workspace_slotManagement | Slot ID only | 密钥管理 | 9D catalog role. |
| PIV catalog / workspace_slotCardAuthentication | Slot ID only | 卡片验证 | 9E catalog role. |
| PIV catalog / workspace_slotRetired | Slot ID only | 退役密钥 | Existing supported retired-key slots only. |
| FIDO list / workspace_credentials | 凭据 ID dropdown | 凭据 | Actual inventory rows only; IDs and account names wrap. |
| FIDO list / workspace_credentialReadRequired | Empty credential choices | 选择 RP 后，明确执行“凭据 · 读取”。当前列表不代表卡片没有凭据。 | Empty local data does not imply observed absence; no automatic enumeration. |
| Task modal / workspace_tasks | 操作 dropdown | 选择任务 | Scrollable native modal retains all existing common and advanced operations. |
| Completed report / workspace_receipt | Operation/result rows | 任务结果 | Mark retained output as task evidence. |
| Completed report / workspace_receiptOwner | Captured rows beside mutable form | 以下结果属于本次任务记录的对象。改变当前选择不会改变结果中的对象。 | Retain original report/export ownership; backend may retire obsolete reports as before. |

Existing common controls (`操作`, `取消`, `确认`, `读取`, `导出`, `凭据提供者`), destination labels, operation titles and field labels remain unchanged. Full reader, RP, credential and report values wrap; technical evidence is disclosed separately while retry/uncertainty/consequence messages stay visible. Secrets retain existing reveal/export consent. Returning to the object list clears local secret fields and pending confirmation; it never dispatches, persists secrets or repeats uncertain writes.

Wide layout uses one 280 dp object pane and one detail pane when remaining width at the current font scale and height can contain native controls. Compact height/large font return to one pane. The four native destination targets remain in the safe bottom area with IME padding. Source/build verification is separate from installed-device, TalkBack, hardware-card and external-consumer acceptance.

Fresh FIDO/PIV startup and destination navigation enter the object list. Actual active signing/save restoration enters its existing owned detail. Card-wide tasks require deliberate overview selection; observed slot/RP/credential rows open only their selected detail. Existing task/IME regression fixtures now explicitly enter the overview before testing their original input/receipt behavior.
