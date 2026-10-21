# Provider consumer copy and workflow review

Issue #33. This record supersedes the provider-specific retained/deferred rows in the [manager review](CONSUMER-COPY-REVIEW.md) for this separate provider package. The manager record remains the historical record of manager scope and its passed evidence. Before values here are from the verified manager parent `b68a040`; after values apply only when this provider package is installed. No provider activation, device/card qualification or real-site acceptance is claimed.

Reviewed with plain-language-iso-24495: the reader must identify the verified website, requesting application and account, understand creation/attestation or write consequences, perform the named task, and recover from a real failure. Do not expose a manual recognition checklist or imply that handing a response to Android means a website accepted it.

## Task progression and consent

The initial named action follows verified request context and creation/attestation disclosure. It authorizes card connection and the actual operation when fresh card/request policy does not require a PIN. Fresh recognition is internal. If actual policy requires PIN, the existing leased session waits for explicit secure submission. Fresh retry counts are shown; one attempt remaining changes the action to an explicit single-attempt action with the real lock/reset consequence. Retry facts are checked again before authentication; changed facts fail without authenticating or automatically prompting again. No PIN is requested for actual no-PIN/built-in-UV paths. Existing manager U2F compatibility is untouched; provider remains its existing CTAP2 public-key path.

Reader selection and USB permission remain real prerequisites. A denied permission or unavailable/disabled NFC gives its actual next step while preserving the unstarted task; it does not reserve transport or claim success. No authentication/write is automatically replayed. Request snapshots, caller/DAL/origin/clientData checks, claim reservation, operation lease, secure window, cancellation and unknown-write behavior remain.

## Exact provider resource review

All provider resources are included below, including unchanged and deleted entries. Standard NFC/USB labels and dynamic website/package/account/card identifiers remain exact. Native Material buttons and text fields provide their label semantics; account buttons merge their actual name/username and disambiguation text. Unnamed or colliding choices use truthful one-based account numbers plus full credential identifiers, not a bare number as invented identity.

| Screen / resource key | Before | After | Reason / activation |
|---|---|---|---|
| Provider / `plexo_provider_account` | 账户 | 账户 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_accounts` | 选择账户 | 选择账户 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_caller` | 调用应用 | 请求应用 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_cancel` | 取消 | 取消 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_close` | 关闭 | 关闭 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_confirm` | 确认 | [removed] | Removed manual-recognition/generic-confirmation copy; traced all runtime/XML/test consumers. |
| Provider / `plexo_provider_confirmation` | 确认操作 | [removed] | Removed manual-recognition/generic-confirmation copy; traced all runtime/XML/test consumers. |
| Provider / `plexo_provider_create_consequence` | 将在这张卡上创建 FIDO 凭据。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。 | 将在连接的卡片上创建通行密钥。存储在卡片上的凭据会占用卡内空间，无法由 CardKey 复制到另一张卡。 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_create_title` | 确认创建凭据 | 创建通行密钥 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_executing` | 正在处理凭据请求 | 正在处理凭据请求 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_get_consequence` | 将使用这张卡登录所列网站。 | 将向所列网站提供一次登录验证。 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_get_title` | 确认本次登录 | 使用通行密钥登录 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_keep_nfc` | 请保持卡片贴近手机。连接中断后，本次请求会结束。 | 请保持卡片贴近手机，直到操作完成。 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_permission` | 等待 USB 访问授权 | 等待 USB 访问授权 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_permission_prompt` | 在系统提示中决定是否允许 %1$s 访问此 USB 读卡器。 | 在系统提示中决定是否允许 %1$s 访问此 USB 读卡器。 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_reader` | 读卡器 | 连接方式 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_recognition` | 先识别卡片 | [removed] | Removed manual-recognition/generic-confirmation copy; traced all runtime/XML/test consumers. |
| Provider / `plexo_provider_recognition_explanation` | 先读取卡片的 FIDO2 信息。识别后再确认创建或登录；需要 PIN 时才显示输入框。 | [removed] | Removed manual-recognition/generic-confirmation copy; traced all runtime/XML/test consumers. |
| Provider / `plexo_provider_recognition_nfc` | 请将卡片贴近手机，读取 FIDO2 信息。 | 请将卡片贴近手机。 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_recognize` | 识别卡片 | [removed] | Removed manual-recognition/generic-confirmation copy; traced all runtime/XML/test consumers. |
| Provider / `plexo_provider_recognized` | 已读取 FIDO2 信息。请确认本次操作。 | [removed] | Removed manual-recognition/generic-confirmation copy; traced all runtime/XML/test consumers. |
| Provider / `plexo_provider_recognizing` | 正在识别卡片 | 正在连接卡片… | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_rejected` | 请求未通过核验 | 请求未通过核验 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_result` | 本次请求结果 | 本次请求结果 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `plexo_provider_rp` | 网站 · RP | 网站 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_title` | 系统凭据请求 | [removed] | Removed redundant technical heading; existing app_name CardKey is the sole app-bar title. |
| Provider / `plexo_provider_verified` | 此页面来自系统凭据请求。调用应用与 RP 已通过核验。 | 网站与请求应用已通过核验。 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `plexo_provider_verifying` | 正在核验请求 | 正在核验请求 | Reviewed unchanged; retain actual state/action meaning. |
| Provider / `provider_accountNumber` | [new] | 账户 %1$d | Truthful local numbering and complete disambiguation when actual names are absent or collide. |
| Provider / `provider_createAction` | [new] | 创建通行密钥 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_credentialId` | [new] | 通行密钥编号：%1$s | Truthful local numbering and complete disambiguation when actual names are absent or collide. |
| Provider / `provider_finalAttemptCreate` | [new] | 验证一次并创建 | Only fresh one-attempt state; explicit single authentication submission under the same lease. |
| Provider / `provider_finalAttemptGet` | [new] | 验证一次并继续登录 | Only fresh one-attempt state; explicit single authentication submission under the same lease. |
| Provider / `provider_getAction` | [new] | 继续登录 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_pin` | [new] | FIDO PIN | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_pinChange` | [new] | 请先在 CardKey 管理界面更改 FIDO PIN，再重新发起本次请求。本次未验证 PIN。 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_pinSetup` | [new] | 请先在 CardKey 管理界面设置 FIDO PIN，再重新发起本次请求。本次未验证 PIN。 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_remainingAttempts` | [new] | PIN 剩余尝试：%1$d 次 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_unlock` | [new] | 解锁卡片 | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_userId` | [new] | 账户 ID | Provider package: identify the user task, actual state or conditional next action. |
| Provider / `provider_username` | [new] | 用户名 | Provider package: identify the user task, actual state or conditional next action. |
| Shared provider use / `app_name` | CardKey | CardKey | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `confirm_blobWrite` | 将写入此凭据的 Large Blob。若共享数据已损坏，写入会重建存储，其他凭据的 Large Blob 数据可能丢失。确认写入？ | 将写入此凭据的 Large Blob。若共享数据已损坏，写入会重建存储，其他凭据的 Large Blob 数据可能丢失。确认写入？ | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `confirm_fidoLastRetry` | FIDO PIN 仅剩一次尝试。输入错误将锁定 PIN，可能需要重置设备并重新绑定账户。仅验证一次？ | FIDO PIN 仅剩一次尝试。输入错误将锁定 PIN，可能需要重置设备并重新绑定账户。仅验证一次？ | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_authorization` | 卡片未授权此操作。 | 卡片未授权此操作。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_cancelled` | 操作已取消。 | 操作已取消。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_capacityExceeded` | 卡片存储空间不足，或已达到此操作的数量或大小限制。 | 卡片存储空间不足，或已达到此操作的数量或大小限制。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_cardRejected` | 卡片拒绝了此操作。请检查卡片状态及操作前提。 | 卡片拒绝了此操作。请检查卡片状态及操作前提。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_hidPhysicalActionRequired` | 请重新发起操作，再触摸安全密钥或按下按键。 | 请重新发起操作，再触摸安全密钥或按下按键。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_hidPowerCycleRequired` | PIN 验证暂时锁定。请断开安全密钥的所有电源，再重新连接。 | PIN 验证暂时锁定。请断开安全密钥的所有电源，再重新连接。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_invalidInput` | 输入格式有误，请检查所填内容。 | 输入格式有误，请检查所填内容。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_malformed` | 卡片响应无效。 | 卡片响应无效。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_nfcDisabled` | NFC 未开启。请开启 NFC，或连接受支持的 USB 读卡器。 | NFC 未开启。请开启 NFC，或连接受支持的 USB 读卡器。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_nfcUnavailable` | 此设备不支持 NFC。请连接受支持的 USB 读卡器。 | 此设备不支持 NFC。请连接受支持的 USB 读卡器。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_physicalActionRequired` | 请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。 | 请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_pinBlocked` | PIN 或 PUK 已锁定。 | PIN 或 PUK 已锁定。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_pinInvalid` | PIN 或 PUK 不正确。请确认后重新发起操作。 | PIN 或 PUK 不正确。请确认后重新发起操作。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_recoveryRequired` | 卡片状态与预期不符。请先读取卡片状态，勿重复提交更改。 | 卡片状态与预期不符。请先读取卡片状态，勿重复提交更改。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_transport` | 卡片连接失败。 | 卡片连接失败。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `error_unsupported` | 此卡片或读卡器不支持这项操作。 | 此卡片或读卡器不支持这项操作。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `operation_appliedReadbackFailed` | 卡片已接受更改，但回读未完成。请读取卡片状态，勿重复提交更改。 | 卡片已接受更改，但回读未完成。请读取卡片状态，勿重复提交更改。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `operation_notDispatched` | 指令未发送到卡片。 | 指令未发送到卡片。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `operation_unknown` | 指令已发送，但未收到结果。更改可能已生效；请先读取卡片状态，再决定下一步。 | 指令已发送，但未收到结果。更改可能已生效；请先读取卡片状态，再决定下一步。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `provider_attestation_direct` | 该服务请求卡片证明，响应可能包含可识别这张卡的证书。 | 该网站请求卡片证明，响应可能包含可识别这张卡的证书。 | Provider package: preserve privacy/effect facts using reader-facing language. |
| Shared provider use / `provider_attestation_none` | 本次注册不发送卡片证明证书，AAGUID 将置零。 | 本次创建不发送卡片证明证书，并隐藏设备类型标识。 | Provider package: preserve privacy/effect facts using reader-facing language. |
| Shared provider use / `provider_choose_account` | 选择用于登录 %1$s 的账户。 | 选择用于登录 %1$s 的账户。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `provider_confirm_fido` | FIDO PIN 输入错误会减少重试次数。确认继续？ | [removed] | Removed unconditional generic incorrect-PIN reminder; actual retry count and conditional final-attempt consequence remain. |
| Shared provider use / `provider_request_rejected` | 无法验证请求应用或请求内容，未向卡片发送指令。 | 无法验证请求应用或请求内容，未向卡片发送指令。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `provider_settings` | 在 Android 设置中选择并启用 %1$s，然后返回。 | 在 Android 设置中选择并启用 %1$s，然后返回。 | Retained: Android settings must be selected by the user; opening settings does not prove activation. |
| Shared historical use / `reader_nfc` | 选择操作后，将卡片贴近手机。 | 选择操作后，将卡片贴近手机。 | Removed from the provider permanent reader hint; retained by the manager progress caller. |
| Shared provider use / `reader_permissionDenied` | 未获 USB 读卡器访问授权。请重新选择读卡器，或改用 NFC。 | 未获 USB 读卡器访问授权。请重新选择读卡器，或改用 NFC。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |
| Shared provider use / `status_hidUserPresenceRequired` | 请触摸安全密钥或按下按键。 | 请触摸安全密钥或按下按键。 | Reviewed unchanged shared text; preserve the actual permission, effect, user-presence or failure condition. |

## Hardcoded UI, accessibility and installation coverage

| Location / expression | Before | After | Review decision |
|---|---|---|---|
| ProviderActivity / account title | displayName, then name, then bare `${index + 1}` | Actual nonblank names; localized 账户 n and full credential ID for ambiguity | No fabricated identity; request website stays visible |
| ProviderActivity / create account | Empty text if both names missing | Localized 账户 1 with actual verified-request account ID | Empty identity is not silently accepted as readable context |
| ProviderActivity / app-bar title | 系统凭据请求 above CardKey | CardKey | Existing app identity only; actual operation remains in verified body context |
| ProviderActivity / PIN label | FIDO PIN (literal) | `provider_pin`: FIDO PIN | Resource-backed, secure input only after actual policy |
| ProviderActivity / NFC and USB | NFC / USB | NFC / USB | Retained standard connection labels; product names are actual Android device metadata |
| ProviderActivity / primary action | 识别卡片, then generic 确认 | Named create/login action; conditional single-attempt wording | Internal preparation replaces manual protocol stage |
| ProviderActivity / result actions | Close plus unconditional Cancel | Close for rejected/terminal result; Cancel for active task/account choice | Remove duplicate terminal controls; unknown write has no repeat action |
| ProviderActivity / progress | Reading FIDO2 information and separate recognized message | Connecting/processing or concrete NFC presentation instruction | State is operation-owned; no invented readiness |
| CardKeyProviderService / label | `app_name`: CardKey | CardKey | Retained actual provider identity; no credential inventory cached on phone |
| Credential provider manifest/capability | Existing service, binding permission and public-key capability | Unchanged | No new permission, host, algorithm or protocol support |
| Android provider installation/settings help | Enable CardKey through its settings entry | Enable CardKey in Android settings; verified task then actual conditional PIN | Actual user activation remains separate from source/build evidence |

Source-authored remaining provider literals are `%02x` for exact public ID formatting, NFC/USB standard labels and protocol/action/MIME identifiers; none is a generated account name or claimed site success. All new human copy is resource-backed. Shared manager-only PIV/erasure resources remain under their existing callers and were not broadened into provider policy. The conditional provider final-attempt state uses the existing FIDO lock/reset consequence.

## Proven removals and preserved boundaries

Manual recognize/explanation/recognized/confirm resources and the unconditional PIN reminder have no remaining runtime, XML or test caller. Their user-step ownership is replaced by the existing initial task action and conditional secure PIN submission; no new orchestration framework is introduced. Bare numeric/blank account-label fallbacks are replaced by explicit numbers with actual disambiguation. Permission denial and NFC unavailability are known connection outcomes, not fake card success. Parser/verifier fail-closed catches, transport cleanup, extension buffer disposal and dispatch/effect accounting remain.

Provider records supersede the manager ledger's deferred provider wording and workflow. Manager evidence remains valid for unchanged manager code; provider build/test evidence and hardware/account acceptance are recorded separately.

## Synthetic visual verification

The native-graphics Compose fixture exports optional test-only PNGs using the existing manager fixture's `View.draw` into Bitmap/Canvas. Compose `captureToImage` could not receive its redraw callback in paused Robolectric windows and timed out after 2000 ms in `WindowCapture.forceRedraw`; that capture-only failure is not hidden or treated as a product result. The normal verified task, empty PIN-required and simulated-IME frames contain only synthetic request data and no PIN entry. They are not installed-app or real-card acceptance.

The measured short-window/IME regression initially left 29 dp for an 80 dp secure field. Compact windows now retain a native horizontal task/cancel dock and omit the redundant top bar. The test compares the scroll viewport against the unclipped measured field height and keeps both task controls above the simulated keyboard.

Preview dimensions are explicit: normal verified and empty PIN-required portrait fixtures use 360×800 dp at 100% text; the simulated-IME fixture retains 360×480 dp at 150% text and a 240 dp keyboard inset. Existing short-window/large-text checks remain enabled.

Final one-attempt consent is shown in a native conditional dialog using the existing lock/reset warning before the PIN is handed to the runner. It is bound by identity to the current immutable recognition snapshot, remains under the existing operation lease, and is cleared by cancellation, backgrounding and terminal results. Ordinary PIN submissions have no extra dialog. Fresh retry count appears before the secure field. The sole portrait app-bar title is the existing CardKey name; no new label or public runtime API is introduced.

## Software verification evidence

The provider/protocol/confirmation regression checkpoint passed 207 cases with zero failures/skips. The later 211-case run passed its other 208 cases and failed only three optional `captureToImage` exports for the documented redraw timeout; replacing that unavailable capture path with the existing native View.draw method passed the 33 presentation/public-retry cases plus build/lint. The final sole-title and conditional-consent round reused unchanged protocol evidence: current-snapshot/decline/background checks passed, and after correcting the count's placement, all 15 provider presentation and activity-lifecycle cases passed with APK and instrumentation compilation and lint. Final lint reports 0 errors and 10 retained protocol/dependency warnings.

The public retries API is separately checked to retain PIN and optional built-in-UV retry counts with one GetInfo read. No dependency, manifest permission, caller/DAL/origin rule, wire protocol or provider storage policy changed. Source, synthetic tests/previews, Android activation, real reader/card interaction and actual site acceptance remain separate evidence.

## Integrated delivery evidence

The [post-parent validation and delivery record](PROVIDER-CONSUMER-VALIDATION.md) records the normal manager merge, the existing 26-case integration result, verified debug APK aliases and the already inspected native fixture PNGs. It does not extend the activation or hardware/site acceptance scope above.
