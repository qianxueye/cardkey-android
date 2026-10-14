# Plexo provider copy record

Scope: `ProviderActivity.ProviderContent`, Android system-launched credential create/get requests. Copy was reviewed with the plain-language skill for relevance, findability, clarity and usable actions. Activation is limited to the native provider surface in this candidate; no manager destination, request replay, provider registration change, installation or device/card acceptance is implied.

## Exact changes

All new resource keys are in `app/src/main/res/values/plexo_provider.xml`. Identity values remain runtime data after `CallerVerifier.verify`; none of the design fixture identities enter application source.

| Screen/key | Before | After | Reason and activation |
| --- | --- | --- | --- |
| Provider/title | `凭据提供者`, with app name in top bar | `系统凭据请求`; app name remains a secondary top-bar line | Identify the system request surface; all provider states. |
| Provider/create title | Part of `为 %1$s 在这张卡上创建 FIDO 凭据？请求应用：%2$s。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。` | `确认创建凭据` | Distinguish creation from login; verified create requests only. |
| Provider/get title | `使用这张卡登录 %1$s？请求应用：%2$s。` | `确认本次登录` | Identify the requested operation; verified get requests only. |
| Provider/verified | No separate introduction | `此页面来自系统凭据请求。调用应用与 RP 已通过核验。` | State provenance only after verification succeeds; retained verified request context. |
| Provider/rp | RP embedded as `%1$s` in confirmation | `网站 · RP`, followed by the actual RP | Keep long identifiers readable; verified requests only. |
| Provider/caller | `请求应用：%2$s。` | `调用应用`, followed by the actual calling package | Separate caller identity from operation consequences; verified requests only. |
| Provider/account | Unlabeled actual create user display name/name | `账户`, followed by the same actual value | Label the account supplied by the create request; no invented get account. |
| Provider/verifying | Spinner only | `正在核验请求` | Name pending verification; loading only. |
| Provider/rejected | Existing rejection message only | `请求未通过核验`, followed by the unchanged rejection message | Make rejection easy to locate; no verified identity rendered. |
| Provider/result | Existing result messages only | `本次请求结果`, followed by the unchanged `ConfirmationPolicy` result messages | Keep effects and uncertainty attached to this request. |
| Provider/accounts | Existing account-choice prompt and returned options | `选择账户`, followed by the same prompt and options | Label actual returned assertion choices; no fabricated success page. |
| Provider/reader | Unlabeled NFC/USB chips | `读卡器`, followed by the same actual choices | Vertical choices preserve full reader names at narrow widths. |
| Provider/permission title | No separate title | `等待 USB 访问授权` | Distinguish native permission from card execution; pending permission only. |
| Provider/permission prompt | `请在系统提示中允许 %1$s 访问此 USB 读卡器。` | `在系统提示中决定是否允许 %1$s 访问此 USB 读卡器。` | Preserve the app/USB target and system ownership while making the choice explicit. Confirmation and transport selection are disabled during pending permission. |
| Provider/confirmation | Unlabeled PIN and actions | `确认操作` | Group PIN, material consequences and confirmation for the current request. |
| Provider/create consequence | Full create question above | `将在这张卡上创建 FIDO 凭据。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。` | Keep card storage and transfer limits while RP/caller move to labeled facts. |
| Provider/get consequence | Full get question above | `将使用这张卡登录所列网站。` | Keep the physical-card consequence with the labeled verified RP. |
| Provider/confirm, cancel, close | `确认`, `取消`, `关闭` literals | Same exact text in local resources | Preserve familiar action wording; full-width native targets. |

Unchanged material text includes attestation none/direct boundaries, large-blob write confirmation, PIN retry consequences, native presence requests, actual assertion account names and all qualified failure/effect messages. NFC/USB/PIN protocol labels remain exact. Parser, request extraction, client-data verification, extensions, secure-window flag, secure input retirement, lifecycle, permission callbacks and system response contracts remain authoritative.

## Qualification boundary

The candidate adds a scrollable, IME-inset-aware provider body, wrapping identity facts and vertical reader targets. It uses the shared native theme and section/fact components. Confirmation remains disabled while busy or awaiting USB permission and additionally requires verified client data. The NFC choice is also disabled while the system USB permission decision is pending. No selection dispatches a card operation. The existing activity owns confirmation, cancellation and completion.

Source inspection and copy review do not establish rendered geometry, TalkBack, installed-device behavior, hardware or external-account acceptance. Integrated tests/build/lint and native create/get, long names, large font, narrow/landscape/wide layouts, light/dark and IME acceptance remain Root-owned gates.

## Recognition before confirmation

Activation: the provider now reserves one verified request, acquires one owned card session and reads FIDO2 information after the user selects **识别卡片**. The final confirmation appears only after that read succeeds. A PIN field appears only when the observed authenticator capabilities and this request require PIN authentication. The same session remains owned until final confirmation and completion or retirement. This is an observation, not continuous card-presence assurance.

| Screen/key | Before | After | Reason and activation |
| --- | --- | --- | --- |
| Provider/plexo_provider_recognition | PIN and confirmation before a card read | `先识别卡片` | Name the first step; before recognition. |
| Provider/plexo_provider_recognition_explanation | No separate explanation | `先读取卡片的 FIDO2 信息。识别后再确认创建或登录；需要 PIN 时才显示输入框。` | Explain when the operation and secret input become available. |
| Provider/plexo_provider_recognize | `确认` before connection | `识别卡片` | The first action only reads protocol information; it does not authorize credential creation, authentication or writes. |
| Provider/plexo_provider_recognizing | Spinner only | `正在识别卡片` | Identify the pending no-secret read. |
| Provider/plexo_provider_recognition_nfc | No distinct recognition prompt | `请将卡片贴近手机，读取 FIDO2 信息。` | Explain the first NFC interaction while waiting for a tag. |
| Provider/plexo_provider_recognized | No successful-read context | `已读取 FIDO2 信息。请确认本次操作。` | Describe an observed read without claiming continuous presence or same-card authentication. |
| Provider/plexo_provider_keep_nfc | No held-session instruction | `请保持卡片贴近手机。连接中断后，本次请求会结束。` | Keep the NFC session available; no reconnect or replay is offered. |
| Provider/plexo_provider_executing | Spinner in confirmation | `正在处理凭据请求` | Separate the begun credential operation from recognition. |
| Provider/PIN | Always visible before connection | Same `PIN` label, shown only after recognition when required | Prevent secret entry before verified request, native permission and supported recognition; hide and clear on confirmation, cancellation, pause or terminal result. |
| Provider/plexo_provider_confirm | `确认` starts connection and operation | Same `确认`, now resumes the held session after recognition | Preserve explicit operation consent and existing attestation, authentication and write consequences. |

The changed text was reviewed using `plain-language-iso-24495`. Existing RP/caller identity, native permission choice, attestation and storage consequences, uncertainty/effect result messages and secure-window handling remain in the provider. Recognition does not assert a successful credential operation, physical acceptance or deployment.
