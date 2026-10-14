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

Short landscape at sufficient width places the run/cancel group beside the same four bottom destinations to retain readable input space. Phone widths keep the action group above navigation. Both layouts preserve native target sizing and consumed IME/navigation insets; inset regression tests assess actual destination/action boundaries and the retained input viewport.

| Screen / key | Before | Applied wording | Reason / activation |
| --- | --- | --- | --- |
| Device / workspace_pivReaderRequired | Disabled PIV/CA destinations without a transport explanation | 当前读卡器使用 CTAP，只能访问 FIDO。PIV 和证书申请需要 NFC 或支持 APDU 的 USB 读卡器。 | Explain actual existing capability guards; no new transport or action. |
| SSH receipt / workspace_outputFile | Export button without captured filename | 本次导出文件 | Display the captured report filename, never the current form slot. |
| SSH receipt / OpenSSH | Public report export bytes only | OpenSSH | Existing technical disclosure presents exact captured public export text with native selection; no private key/PIN or login claim. |

The integrated lint gate identified five source-unused legacy resources after the approved provider and enrollment copy replacements. A complete `app/src` reference search confirmed no Kotlin/XML consumer; these definitions were removed rather than suppressing lint. Active replacement wording remains in the owning provider/CA copy records.

| Screen / retired key | Before | After | Reason / activation |
| --- | --- | --- | --- |
| Provider / provider_create_confirmation | 为 %1$s 在这张卡上创建 FIDO 凭据？请求应用：%2$s。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。 | Definition removed; provider's new separate caller/RP and create confirmation remain active. | Proven unused after the same package's provider layout replacement. |
| Provider / provider_get_confirmation | 使用这张卡登录 %1$s？请求应用：%2$s。 | Definition removed; provider's new separate caller/RP and get confirmation remain active. | Proven unused after the same package's provider layout replacement. |
| Enrollment / enrollment_root | CA 根证书 | Definition removed; actual CA preparation heading remains active. | Proven unused after the same package's CA layout replacement. |
| Enrollment / enrollment_import | 导入证书 | Definition removed; 检查导入确认 remains active. | Proven unused; explicit confirmation semantics retained. |
| Enrollment / enrollment_status | 证书申请 | Definition removed; actual task/result headings remain active. | Proven unused after the same package's CA layout replacement. |

## Recognition prerequisite for card-secret entry

Owner corrected the initial task-preview behavior: card PIN, PUK and management-key fields must not render before actual recognition. ManagerScreen now consumes the runtime-owned route/lifecycle/family record and `canEnterFidoSecrets` / `canEnterPivSecrets`. A selected USB descriptor, NFC mode, transport support, inventory or historical result never creates this prerequisite. U2F/PIV recognition cannot unlock FIDO2 secrets, and FIDO2 recognition cannot unlock PIV secrets. These records are last observed on a route; they do not prove ongoing presence or authorize a card operation.

Secret fields and their submit action require the matching record. Public slot/algorithm/file/task inputs and no-secret information reads remain available. The prerequisite control explicitly executes only the existing matching information read through onSubmit, retaining the prospective task/slot/file preparation. It is a separate read action; object/task selection still never dispatches. NFC retains its owned queued-read/tap flow. Local PIN/new PIN/PUK/admin/extension secrets and pending confirmation retire when recognition identity or permission state changes. Confirmation also compares its captured recognition record at dispatch. The same identity retirement advances the existing CA/TLS clear-input parameter without recreating public preparation state; existing canUseCard includes actual PIV recognition and previous transport/uncertainty requirements.

| Screen / key | Before | Applied wording / behavior | Reason / activation |
| --- | --- | --- | --- |
| FIDO task / workspace_readFidoFirst | PIN and extension-secret fields rendered in unrecognized task preview | 先执行“FIDO2 · 读取”，识别认证器后再输入 PIN 或扩展数据。读取记录不代表卡片持续在场。 | Explain the actual FIDO2 prerequisite and observation scope; manager card-secret tasks only. |
| PIV task / workspace_readPivFirst | PIN/PUK/admin fields rendered in unrecognized task preview | 先执行“PIV · 读取”，识别卡片后再输入 PIN、PUK 或管理密钥。读取记录不代表卡片持续在场。 | Explain the actual PIV prerequisite and observation scope; manager card-secret tasks only. |
| Task prerequisite / workspace_readRecognition | No prerequisite control | 读取卡片信息 | Explicitly dispatches existing matching FIDO_INFO or PIV_INFO without changing the prospective task or slot; preserves the runtime-approved prepared-file owner, never signing authority. |
| Manager and CA/TLS secret fields | Visible without recognition | Hidden until matching runtime-owned recognition; retired on identity change | Preserve public prep/exports and existing submit/uncertainty/busy guards; recognition is not authorization. |

Existing private-input/IME regression fixtures now supply explicit matching recognized-state snapshots. No-card task navigation keeps the default null recognition and no secret fields. Original inset, input-space, focused-field, retry, confirmation and report/export ownership assertions remain. Runtime producer tests separately exercise actual recognition provenance and retirement; these UI fixtures do not establish device/card acceptance.

| PIV key generation / PIN policy selector | PIN | PIN 验证策略 | Identify the retained public policy choice separately from card-secret PIN entry; preparation remains available before recognition. |

The explicit recognition pulse carries the current slot and inventory generation; FIDO pulses also carry the current selected credential. Runtime preservation is limited to the currently owned matching RAW/CMS prepared file or selected-credential blob-write file. Keys, PINs, stale requests, authorization and prior operation success never transfer through the read. A fresh explicit credential verification still supplies any one-use blob key. Public algorithm/text/file preparation is not recreated merely because recognition changes; existing genuine task/reader retirement remains.
