# Consumer manager copy review

Related issue: #33. Reviewed with the plain-language-iso-24495 workflow. Activation is this Android manager runtime package after installation; no installation, provider enablement, hardware or real-account acceptance is claimed. Provider workflow behavior remained a separate package at this manager checkpoint. The [provider consumer review](PROVIDER-CONSUMER-COPY-REVIEW.md) supersedes all provider-specific retained/deferred rows below for the subsequent provider package; manager scope and evidence remain historical and unchanged.

The consumer task is to view passkeys, prepare its PIN, connect the card, and receive a complete inventory. Retain concrete irreversible consequences and uncertain results at the relevant action. Do not repeat generic PIN-risk or manual status-read reminders. Ordinary inventory and rename have no redundant generic confirmation. Delete keeps the account-loss confirmation. Final-attempt consent uses fresh card facts under the operation lease; dismissal or backgrounding declines authentication. No PIN is persisted or replaced with phone biometrics.

## Applied navigation and interaction changes

| Screen / source | Before | After | Reason and activation |
|---|---|---|---|
| Manager initial task | Device page; manual FIDO read before PIN | Passkeys page with secure FIDO PIN input and 继续 | Prepare the user's task before card connection; manager package |
| Passkey selection | Read RP → select RP → read credentials; duplicate detail selectors | One complete account list from one token scope | Remove protocol steps and duplicate selection; manager package |
| Account rename | Raw credential ID HEX + 名称 | Immutable selected website/account + 新用户名 | Bind action to visible target; manager package |
| Delete confirmation | Generic operation plus optional raw ID | Website and account from owned immutable target + retained loss consequence | Show the object actually deleted; manager package |
| Navigation | Device / passkeys / certificates / settings | Card / passkeys / certificates / settings | User task vocabulary, native controls retained |
| Settings | Provider/enrollment only; debug actions mixed with task menu | Explicit advanced FIDO settings/diagnostics and advanced PIV administration/diagnostics | Keep legitimate protocol capability outside normal user tasks |
| Provider-settings dialog button | 启用 | 打开设置 | It opens Android settings; it cannot prove activation |
| Manager reader header | Permanent unread/last-result/general freshness reminders | Connection identity and conditional uncertain-result warning | Keep current task readable without weakening uncertainty fences |
| Document input/output | Null or exceptions silently discard results | File-read failure, saved, cancelled or unconfirmed document outcome separate from card result | Null stream, partial write and close failure never count as saved |

## Resource coverage: exact before and after

Every string in the three resource files below was reviewed. Unchanged entries retain existing data-loss, permission, input, trust and activation conditions; diagnostic wording remains only in its relevant advanced task. Provider-specific entries record the manager baseline; the linked provider consumer review supersedes their wording and activation for the separate provider package. Shared error text remains accurate in both callers.

| Resource / key | Before | After | Review decision / activation |
|---|---|---|---|
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_csr` | 卡内密钥与 CSR | 卡内密钥与 CSR | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_currentRequest` | 本次申请 | 本次申请 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_documentIdentity` | 文档签名证书 | 文档签名证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_exportChain` | 导出已验证的证书链 | 导出已验证的证书链 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_identity` | 申请身份 | 申请身份 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_importExport` | 证书导入与导出 | 证书导入与导出 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_importReview` | 检查导入确认 | 检查导入确认 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_issue` | 向 CA 申请 | 向 CA 申请 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_prepare` | 申请准备 | 申请准备 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_purpose` | 用途 | 用途 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_root` | 签发根证书 | 签发根证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_rootReady` | 已选择 CA 根证书 | 已选择 CA 根证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_service` | 签发服务 | 签发服务 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_slotAlgorithm` | 槽位与算法 | 槽位与算法 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_tlsIdentity` | TLS 客户端证书 | TLS 客户端证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_ca_unknownRequest` | CA 签发结果待核实 | CA 签发结果待核实 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_tls_certificate` | 连接所用证书 | 连接所用证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_tls_result` | 本次连接结果 | 本次连接结果 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_ca.xml` / `plexo_tls_service` | 服务与认证 | 服务与认证 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_account` | 账户 | 账户 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_accounts` | 选择账户 | 选择账户 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_caller` | 调用应用 | 调用应用 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_cancel` | 取消 | 取消 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_close` | 关闭 | 关闭 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_confirm` | 确认 | 确认 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_confirmation` | 确认操作 | 确认操作 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_create_consequence` | 将在这张卡上创建 FIDO 凭据。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。 | 将在这张卡上创建 FIDO 凭据。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_create_title` | 确认创建凭据 | 确认创建凭据 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_executing` | 正在处理凭据请求 | 正在处理凭据请求 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_get_consequence` | 将使用这张卡登录所列网站。 | 将使用这张卡登录所列网站。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_get_title` | 确认本次登录 | 确认本次登录 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_keep_nfc` | 请保持卡片贴近手机。连接中断后，本次请求会结束。 | 请保持卡片贴近手机。连接中断后，本次请求会结束。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_permission` | 等待 USB 访问授权 | 等待 USB 访问授权 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_permission_prompt` | 在系统提示中决定是否允许 %1$s 访问此 USB 读卡器。 | 在系统提示中决定是否允许 %1$s 访问此 USB 读卡器。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_reader` | 读卡器 | 读卡器 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_recognition` | 先识别卡片 | 先识别卡片 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_recognition_explanation` | 先读取卡片的 FIDO2 信息。识别后再确认创建或登录；需要 PIN 时才显示输入框。 | 先读取卡片的 FIDO2 信息。识别后再确认创建或登录；需要 PIN 时才显示输入框。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_recognition_nfc` | 请将卡片贴近手机，读取 FIDO2 信息。 | 请将卡片贴近手机，读取 FIDO2 信息。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_recognize` | 识别卡片 | 识别卡片 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_recognized` | 已读取 FIDO2 信息。请确认本次操作。 | 已读取 FIDO2 信息。请确认本次操作。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_recognizing` | 正在识别卡片 | 正在识别卡片 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_rejected` | 请求未通过核验 | 请求未通过核验 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_result` | 本次请求结果 | 本次请求结果 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_rp` | 网站 · RP | 网站 · RP | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_title` | 系统凭据请求 | 系统凭据请求 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_verified` | 此页面来自系统凭据请求。调用应用与 RP 已通过核验。 | 此页面来自系统凭据请求。调用应用与 RP 已通过核验。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/plexo_provider.xml` / `plexo_provider_verifying` | 正在核验请求 | 正在核验请求 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/strings.xml` / `app_name` | CardKey | CardKey | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `artwork_failed` | 卡片图片加载失败，仍可使用卡片功能。 | 卡片图片加载失败，仍可使用卡片功能。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `artwork_generic` | 通用卡片示意图 | 通用卡片示意图 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `artwork_loading` | 正在加载卡片图片… | 正在加载卡片图片… | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `artwork_retry` | 重试图片 | 重试图片 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `blob_help` | Large Blob 按所选凭据加密。读取或写入前，请先验证该凭据并读取 largeBlobKey；密钥仅供下一次操作使用。 | Large Blob 按所选凭据加密。读取或写入前，请先验证该凭据并读取 largeBlobKey；密钥仅供下一次操作使用。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `certificate_dateScope` | 仅检查证书有效期，未验证信任链、吊销状态或与槽位公钥的匹配关系。 | 仅检查证书有效期，未验证信任链、吊销状态或与槽位公钥的匹配关系。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_adminChange` | 更换后，后续管理操作需要新管理密钥。请先妥善保存新密钥。确认更换？ | 更换后，后续管理操作需要新管理密钥。请先妥善保存新密钥。确认更换？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_alwaysUvDisable` | 关闭后，创建凭据和登录验证按卡片和凭据各自的策略要求用户验证。确认关闭？ | 关闭后，创建凭据和登录验证按卡片和凭据各自的策略要求用户验证。确认关闭？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_alwaysUvEnable` | 开启后，创建凭据和登录验证始终要求用户验证，U2F 将不可用。确认开启？ | 开启后，创建凭据和登录验证始终要求用户验证，U2F 将不可用。确认开启？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_authenticate` | 当前 PIN 或 PUK 输入错误会减少重试次数。确认继续？ | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `confirm_blobExport` | 将导出此凭据解密后的 Large Blob 内容。请保存在受保护的位置。确认导出？ | 将导出此凭据解密后的 Large Blob 内容。请保存在受保护的位置。确认导出？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_blobWrite` | 将写入此凭据的 Large Blob。若共享数据已损坏，写入会重建存储，其他凭据的 Large Blob 数据可能丢失。确认写入？ | 将写入此凭据的 Large Blob。若共享数据已损坏，写入会重建存储，其他凭据的 Large Blob 数据可能丢失。确认写入？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_certificateImport` | 公钥匹配验证通过后，将替换所选槽位的证书。确认导入？ | 公钥匹配验证通过后，将替换所选槽位的证书。确认导入？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_credentialDelete` | 删除所选凭据后，无法再用它登录已绑定的账户。此操作不可撤销。确认删除？ | 删除所选凭据后，无法再用它登录已绑定的账户。此操作不可撤销。确认删除？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_credentialUpdate` | 更新所选凭据的用户信息，用户 ID 保留。确认更新？ | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `confirm_enterprise` | 按卡内既有策略开启企业证明，允许的服务可能据此识别这张卡。确认开启？ | 按卡内既有策略开启企业证明，允许的服务可能据此识别这张卡。确认开启？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_fidoLastRetry` | [new] | FIDO PIN 仅剩一次尝试。输入错误将锁定 PIN，可能需要重置设备并重新绑定账户。仅验证一次？ | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `confirm_fidoReset` | 重置会使此设备上的全部 FIDO2／U2F 凭据失效，清除 FIDO PIN、相关数据和设置，无法撤销。之后需重新绑定账户。其他功能和证明配置是否保留，取决于设备。请按设备要求完成物理确认。确认重置？ | 重置会使此设备上的全部 FIDO2／U2F 凭据失效，清除 FIDO PIN、相关数据和设置，无法撤销。之后需重新绑定账户。其他功能和证明配置是否保留，取决于设备。请按设备要求完成物理确认。确认重置？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_keyGenerate` | 生成新密钥将覆盖所选槽位的现有私钥，原证书可能不再匹配。此操作不可撤销。确认生成？ | 生成新密钥将覆盖所选槽位的现有私钥，原证书可能不再匹配。此操作不可撤销。确认生成？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_lastRetryErasesKeys` | {当前验证项} 仅剩一次重试，{另一项} 已锁定。此次验证失败将清除 PIV 私钥。确认继续？ | {当前验证项} 仅剩一次重试，{另一项} 已锁定。此次验证失败将清除 PIV 私钥。确认继续？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_minPin` | 最短 PIN 长度不能降低。提高长度后，卡片可能要求修改 PIN；选择强制修改时，须先修改 PIN 才能再次进行 PIN 验证。同时替换最短 PIN 长度的披露名单。确认应用？ | 最短 PIN 长度不能降低。提高长度后，卡片可能要求修改 PIN；选择强制修改时，须先修改 PIN 才能再次进行 PIN 验证。同时替换最短 PIN 长度的披露名单。确认应用？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_pinChange` | 当前 PIN 或 PUK 输入错误会减少重试次数。确认修改？ | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `confirm_pivReset` | 仅在 PIN 和 PUK 均已锁定时重置 PIV。PIV 私钥此前已清除；重置将清除 PIV 数据，并恢复默认 PIN、PUK 和管理密钥。FIDO 凭据保留。确认重置？ | 仅在 PIN 和 PUK 均已锁定时重置 PIV。PIV 私钥此前已清除；重置将清除 PIV 数据，并恢复默认 PIN、PUK 和管理密钥。FIDO 凭据保留。确认重置？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_registration` | 将为 cardkey.example 创建测试凭据；驻留凭据会占用卡内存储。确认创建？ | 将为 cardkey.example 创建测试凭据；驻留凭据会占用卡内存储。确认创建？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_secretRead` | 将读取凭据的秘密输出。它不会显示在普通结果中，也不会自动导出。确认读取？ | 将读取凭据的秘密输出。它不会显示在普通结果中，也不会自动导出。确认读取？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `confirm_secretShow` | 秘密输出可用于派生密钥，请勿分享或粘贴到日志。确认显示？ | 秘密输出可用于派生密钥，请勿分享或粘贴到日志。确认显示？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `credential_deleted` | [new] | 通行密钥已删除。重新读取可查看最新列表。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `credential_updated` | [new] | 用户名已更新。重新读取可查看最新列表。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `device_reader` | 连接方式与读卡器 | 连接方式与读卡器 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `document_cancelled` | [new] | 已取消文件操作。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `document_readFailed` | [new] | 文件未能读取，请重新选择文件。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `document_saved` | [new] | 文件已保存。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `document_unknown` | [new] | 未确认文件保存结果。请检查所选位置，勿重复提交卡片操作。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `enrollment_address` | CA 地址（HTTPS） | CA 地址（HTTPS） | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_chooseRoot` | 选择 CA 根证书 | 选择 CA 根证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_cn` | 通用名称（CN） | 通用名称（CN） | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_confirmImport` | 将已验证的证书写入所选 PIV 槽位，替换该槽位的现有证书。私钥不变。确认导入？ | 将已验证的证书写入所选 PIV 槽位，替换该槽位的现有证书。私钥不变。确认导入？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_createCsr` | 生成 CSR | 生成 CSR | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_csrReady` | CSR 已生成，可导出后交给 CA 管理员。 | CSR 已生成，可导出后交给 CA 管理员。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_document` | 文档签名 | 文档签名 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_document9a` | 导入后将替换 9A 的现有证书，该文档签名证书不能用于 TLS 客户端认证。 | 导入后将替换 9A 的现有证书，该文档签名证书不能用于 TLS 客户端认证。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_documentScope` | 使用所选槽位的现有密钥申请文档签名证书。不会生成或替换密钥。 | 使用所选槽位的现有密钥申请文档签名证书。不会生成或替换密钥。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_exportCsr` | 导出 CSR | 导出 CSR | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_imported` | 证书已写入所选槽位，并完成回读。 | 证书已写入所选槽位，并完成回读。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_invalid` | 证书未通过验证，未写入卡片。 | 证书未通过验证，未写入卡片。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_issue` | 申请证书 | 申请证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_issuing` | 正在向所选 CA 申请证书。 | 正在向所选 CA 申请证书。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_purpose` | 客户端认证（TLS） | 客户端认证（TLS） | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_rejected` | CA 拒绝了申请，未写入卡片。 | CA 拒绝了申请，未写入卡片。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_scope` | 令牌必须绑定此 CSR，并由 CA 管理员按所选用途授权。槽位仅决定本地导入位置。 | 令牌必须绑定此 CSR，并由 CA 管理员按所选用途授权。槽位仅决定本地导入位置。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_steps` | 先生成并导出 CSR，再获取 CA 管理员提供的一次性授权令牌。 | 先生成并导出 CSR，再获取 CA 管理员提供的一次性授权令牌。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_title` | PIV／PKI | PIV／PKI | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_token` | 一次性授权令牌 | 一次性授权令牌 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_unknown` | 未确认签发结果。请先向 CA 管理员核实，勿重复提交。 | 未确认签发结果。请先向 CA 管理员核实，勿重复提交。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enrollment_verified` | CA 已签发证书，证书链、用途、身份和公钥验证通过。证书尚未写入卡片。 | CA 已签发证书，证书链、用途、身份和公钥验证通过。证书尚未写入卡片。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `enterprise_mode` | 企业证明模式 | 企业证明模式 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_authorization` | 卡片未授权此操作。 | 卡片未授权此操作。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_cancelled` | 操作已取消。已发送的指令可能已生效，请查看卡片状态。 | 操作已取消。 | Known cancellation stays concise; unknown effects add the existing explicit operation_unknown warning. |
| `app/src/main/res/values/strings.xml` / `error_capacityExceeded` | 卡片存储空间不足，或已达到此操作的数量或大小限制。 | 卡片存储空间不足，或已达到此操作的数量或大小限制。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_cardRejected` | 卡片拒绝了此操作。请检查卡片状态及操作前提。 | 卡片拒绝了此操作。请检查卡片状态及操作前提。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_contactRequired` | 此操作需要接触式读卡器。NFC 目前仅支持读取 PIV 公开信息。 | 此操作需要接触式读卡器。NFC 目前仅支持读取 PIV 公开信息。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_hidPhysicalActionRequired` | 请重新发起操作，再触摸安全密钥或按下按键。 | 请重新发起操作，再触摸安全密钥或按下按键。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_hidPowerCycleRequired` | PIN 验证暂时锁定。请断开安全密钥的所有电源，再重新连接。 | PIN 验证暂时锁定。请断开安全密钥的所有电源，再重新连接。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_invalidInput` | 输入格式有误，请检查所填内容。 | 输入格式有误，请检查所填内容。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_keyMismatch` | 证书公钥与所选槽位不匹配，未导入证书。 | 证书公钥与所选槽位不匹配，未导入证书。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_malformed` | 卡片响应无效。 | 卡片响应无效。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_nfcDisabled` | NFC 未开启。请开启 NFC，或连接受支持的 USB 读卡器。 | NFC 未开启。请开启 NFC，或连接受支持的 USB 读卡器。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_nfcUnavailable` | 此设备不支持 NFC。请连接受支持的 USB 读卡器。 | 此设备不支持 NFC。请连接受支持的 USB 读卡器。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_physicalActionRequired` | 请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。 | 请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_pinBlocked` | PIN 或 PUK 已锁定。 | PIN 或 PUK 已锁定。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_pinChangeRequired` | [new] | 此卡要求更改 FIDO PIN。请先选择“更改 FIDO PIN”。本次未验证 PIN。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `error_pinInvalid` | PIN 或 PUK 验证失败，请查看剩余重试次数。 | PIN 或 PUK 不正确。请确认后重新发起操作。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `error_pinSetupRequired` | [new] | 此卡尚未设置 FIDO PIN。请先选择“设置 FIDO PIN”。本次未验证 PIN。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `error_recoveryRequired` | 卡片状态与预期不符。请先读取卡片状态，勿重复提交更改。 | 卡片状态与预期不符。请先读取卡片状态，勿重复提交更改。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_transport` | 卡片连接失败。 | 卡片连接失败。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `error_unsupported` | 当前卡片或读卡器不支持此操作。 | 此卡片或读卡器不支持这项操作。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `inventory_empty` | [new] | 这张卡没有可列出的通行密钥。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `inventory_failed` | [new] | 通行密钥未能读取。请重新发起读取。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `inventory_loaded` | [new] | 选择账户以修改用户名或删除通行密钥。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `inventory_loading` | [new] | 正在读取通行密钥… | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `inventory_unread` | [new] | 输入 FIDO PIN 后继续，再连接卡片。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `minPin_rps` | 披露最短 PIN 长度的 RP ID（每行一个，最多 2 个） | 披露最短 PIN 长度的 RP ID（每行一个，最多 2 个） | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `minPin_rpsHelp` | 仅这些 RP 可读取最短 PIN 长度。留空将清空名单；名单保存在卡内，客户端无法回读。 | 仅这些 RP 可读取最短 PIN 长度。留空将清空名单；名单保存在卡内，客户端无法回读。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `navigation_certificates` | 证书 | 证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `navigation_device` | 设备 | 卡片 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `navigation_passkeys` | 通行密钥 | 通行密钥 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `navigation_settings` | 设置 | 设置 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `operation_appliedReadbackFailed` | 卡片已接受更改，但回读未完成。请读取卡片状态，勿重复提交更改。 | 卡片已接受更改，但回读未完成。请读取卡片状态，勿重复提交更改。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `operation_completed` | 操作已完成。 | 操作已完成。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `operation_fidoResetReadback` | 设备已接受 FIDO 重置，当前状态已读取。 | 设备已接受 FIDO 重置，当前状态已读取。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `operation_notDispatched` | 指令未发送到卡片。 | 指令未发送到卡片。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `operation_unknown` | 指令已发送，但未收到结果。更改可能已生效；请先读取卡片状态，再决定下一步。 | 指令已发送，但未收到结果。更改可能已生效；请先读取卡片状态，再决定下一步。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `provider_attestation_direct` | 该服务请求卡片证明，响应可能包含可识别这张卡的证书。 | 该服务请求卡片证明，响应可能包含可识别这张卡的证书。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/strings.xml` / `provider_attestation_none` | 本次注册不发送卡片证明证书，AAGUID 将置零。 | 本次注册不发送卡片证明证书，AAGUID 将置零。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/strings.xml` / `provider_choose_account` | 选择用于登录 %1$s 的账户。 | 选择用于登录 %1$s 的账户。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/strings.xml` / `provider_confirm_fido` | FIDO PIN 输入错误会减少重试次数。确认继续？ | FIDO PIN 输入错误会减少重试次数。确认继续？ | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/strings.xml` / `provider_request_rejected` | 无法验证请求应用或请求内容，未向卡片发送指令。 | 无法验证请求应用或请求内容，未向卡片发送指令。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/strings.xml` / `provider_settings` | 在 Android 设置中选择并启用 %1$s，然后返回。 | 在 Android 设置中选择并启用 %1$s，然后返回。 | Historical manager baseline; superseded by the linked provider consumer review. |
| `app/src/main/res/values/strings.xml` / `reader_empty` | 未连接受支持的 USB 读卡器。 | 未连接受支持的 USB 读卡器。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `reader_nfc` | 选择操作后，将卡片贴近手机。 | 选择操作后，将卡片贴近手机。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `reader_permissionDenied` | [new] | 未获 USB 读卡器访问授权。请重新选择读卡器，或改用 NFC。 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `reader_usbPermission` | 请在系统提示中允许 %1$s 访问此 USB 读卡器。 | 请在系统提示中允许 %1$s 访问此 USB 读卡器。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `restriction_fidoReset` | 此卡不提供 CTAP 重置。重装 FIDO applet 需在桌面初始化流程中执行，现有 FIDO 凭据将失效。 | 此卡不提供 CTAP 重置。重装 FIDO applet 需在桌面初始化流程中执行，现有 FIDO 凭据将失效。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `secret_view` | 查看秘密输出 | 查看秘密输出 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `settings_enrollment` | 申请 PIV 证书 | 申请 PIV 证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `settings_provider` | 凭据提供者设置 | 凭据提供者设置 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `settings_services` | 系统与证书服务 | 系统与证书服务 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_chooseFile` | 选择文件 | 选择文件 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_cmsRequirements` | 需要有效的文档签名证书，且证书公钥与所选槽位一致。 | 需要有效的文档签名证书，且证书公钥与所选槽位一致。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_cmsScope` | 导出 .p7s 独立签名，包含签名证书；验证时还需原文件。原文件保持不变。 | 导出 .p7s 独立签名，包含签名证书；验证时还需原文件。原文件保持不变。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_cmsTrust` | 未验证证书信任链、吊销状态或可信时间。 | 未验证证书信任链、吊销状态或可信时间。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_cmsVerified` | CMS 签名验证：通过 | CMS 签名验证：通过 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_exportCancelled` | 已取消导出。 | 已取消导出。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_exportScope` | 导出的是所选文件的独立签名；原文件保持不变。 | 导出的是所选文件的独立签名；原文件保持不变。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_exportUnknown` | 未确认导出结果。请检查所选文件。 | 未确认导出结果。请检查所选文件。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_exported` | 已导出签名。 | 已导出签名。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_limit` | 选择非空文件，最大 1 MiB。 | 选择非空文件，最大 1 MiB。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_selectedFile` | %1$s · %2$d 字节 | %1$s · %2$d 字节 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `signing_verified` | 签名验证：通过 | 签名验证：通过 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `ssh_publicKeyExported` | 已导出 SSH 公钥。 | 已导出 SSH 公钥。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `ssh_publicKeyScope` | 已读取 SSH 公钥。导出后可交给 SSH 服务管理员；私钥仍在卡内。本操作不验证 SSH 登录。 | 已读取 SSH 公钥。导出后可交给 SSH 服务管理员；私钥仍在卡内。本操作不验证 SSH 登录。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `status_hidUserPresenceRequired` | 请触摸安全密钥或按下按键。 | 请触摸安全密钥或按下按键。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `status_verification` | 此结果仅验证卡片返回的密码运算、CSR 或签名，不代表证书受信任或外部账户接受。 | 此结果仅验证卡片返回的密码运算、CSR 或签名，不代表证书受信任或外部账户接受。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_cancelled` | 已取消连接验证。 | 已取消连接验证。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_chooseServerRoot` | 选择服务器根证书 | 选择服务器根证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_confirm` | 将向 %1$s 发送所选证书，并使用卡内私钥进行一次认证签名。PIN 输入错误会减少剩余重试次数。确认继续？ | 将向 %1$s 发送所选证书，并使用卡内私钥进行一次认证签名。PIN 输入错误会减少剩余重试次数。确认继续？ | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_endpoint` | 服务器地址 | 服务器地址 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_endpointHelp` | 填写 HTTPS 域名地址，不带查询参数或片段。服务器须支持 TLS 1.3，证书包含完整域名，测试接口返回无正文的 HTTP 204。 | 填写 HTTPS 域名地址，不带查询参数或片段。服务器须支持 TLS 1.3，证书包含完整域名，测试接口返回无正文的 HTTP 204。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_failed` | 连接验证未完成。 | 连接验证未完成。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_identity` | 使用证书 | 使用证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_mayHaveSubmitted` | 认证可能已提交，请核对服务器记录。 | 认证可能已提交，请核对服务器记录。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_running` | 正在验证连接… | 正在验证连接… | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_scope` | 在本 App 内验证所选证书到指定服务器的连接。 | 在本 App 内验证所选证书到指定服务器的连接。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_serverRoot` | 服务器根证书 | 服务器根证书 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_serverRootHelp` | 用所选根证书验证服务器，仅用于本次测试。 | 用所选根证书验证服务器，仅用于本次测试。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_serverRootReady` | 已选择服务器根证书。 | 已选择服务器根证书。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_start` | 验证连接 | 验证连接 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_success` | 已完成 TLS 握手，服务器返回 HTTP 204。请在服务器记录中核对证书与账户。 | 已完成 TLS 握手，服务器返回 HTTP 204。请在服务器记录中核对证书与账户。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_timeout` | 连接验证已超时。 | 连接验证已超时。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_title` | 验证 TLS 连接 | 验证 TLS 连接 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_unknown` | 未确认认证结果。请先核对服务器记录，勿重复提交。 | 未确认认证结果。请先核对服务器记录，勿重复提交。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `tls_validUntil` | 有效期至 | 有效期至 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `user_updateHelp` | 此卡仅支持修改用户名，不支持修改显示名称。 | 此卡仅支持修改用户名，不支持修改显示名称。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_advanced` | 高级与协议诊断 | 高级与协议诊断 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_authenticator` | 认证器 | 认证器 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_busy` | 操作执行中 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_common` | 常用任务 | 常用任务 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_createOnWebsite` | 新通行密钥由网站发起创建。这里可查看支持驻留的凭据。 | 新通行密钥由网站发起创建。这里可查看支持驻留的凭据。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_credentialReadRequired` | 选择 RP 后，明确执行“凭据 · 读取”。当前列表不代表卡片没有凭据。 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_credentials` | 凭据 | [removed] | Removed unused heading after the single account list replaced the RP/credential split. |
| `app/src/main/res/values/strings.xml` / `workspace_credentialsUnread` | 凭据列表尚未读取；先读取 RP，再读取凭据。 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_details` | 技术详情 | 技术详情 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_disconnected` | 当前连接 · 未选择 USB 读卡器 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_enrollmentFlow` | 生成 CSR → CA 签发 → 验证证书 → 导入并读回 | 生成 CSR → CA 签发 → 验证证书 → 导入并读回 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_fidoPin` | 更改 FIDO PIN | 更改 FIDO PIN | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_inspection` | 结果待核实；请先读取卡片状态 | 结果待核实；请先读取卡片状态 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_lastResult` | 最近操作结果见下方；不代表实时卡片状态 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_objects` | 对象列表 | 返回列表 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `workspace_outputFile` | 本次导出文件 | 本次导出文件 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_pivReaderRequired` | 当前读卡器使用 CTAP，只能访问 FIDO。PIV 和证书申请需要 NFC 或支持 APDU 的 USB 读卡器。 | 当前读卡器使用 CTAP，只能访问 FIDO。PIV 和证书申请需要 NFC 或支持 APDU 的 USB 读卡器。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_pivSecurity` | PIV 状态与安全设置 | PIV 状态与安全设置 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_readFidoFirst` | 先执行“FIDO2 · 读取”，识别认证器后再输入 PIN 或扩展数据。读取记录不代表卡片持续在场。 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_readPivFirst` | 先执行“PIV · 读取”，识别卡片后再输入 PIN、PUK 或管理密钥。读取记录不代表卡片持续在场。 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_readRecognition` | 读取卡片信息 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_readSites` | 读取网站列表 | 查看通行密钥 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `workspace_receipt` | 任务结果 | 任务结果 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_receiptOwner` | 以下结果属于本次任务记录的对象。改变当前选择不会改变结果中的对象。 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_security` | 安全设置 | 安全设置 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_selectedObject` | 当前选择 | 当前选择 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_selectedSlot` | 槽位 · %1$s | 槽位 · %1$s | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_selectionOnly` | 选择对象或任务不会发送卡片指令。 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.
| `app/src/main/res/values/strings.xml` / `workspace_sites` | 网站与凭据 | 网站与通行密钥 | Applied manager task/outcome copy; no claim of installation or hardware acceptance. |
| `app/src/main/res/values/strings.xml` / `workspace_slotAuthentication` | 身份验证 | 身份验证 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_slotCardAuthentication` | 卡片验证 | 卡片验证 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_slotCatalog` | 槽位目录 | 槽位目录 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_slotContext` | 槽位选择用于本次操作；证书状态需单独读取。 | 槽位选择用于本次操作；证书状态需单独读取。 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_slotManagement` | 密钥管理 | 密钥管理 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_slotRetired` | 退役密钥 | 退役密钥 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_slotSignature` | 数字签名 | 数字签名 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_tasks` | 选择任务 | 选择任务 | Reviewed unchanged; retain precise meaning and conditions. |
| `app/src/main/res/values/strings.xml` / `workspace_unread` | 卡片状态尚未读取 | [removed] | Removed proven orphan reminder or generic confirmation; required conditional/destructive consequences remain.

## Hardcoded authored copy and accessible text

The following ledger records each authored Chinese UI/receipt source line at the review baseline and this candidate. A removed line is shown as [removed]; newly written copy is [new]. Repeated unchanged source expressions are recorded once per file. Dynamic card/RP/account/certificate/file values retain their actual values; they are not fabricated localized messages. Binary IDs and policy/protocol names remain exact in technical details or when needed to distinguish otherwise identical accounts. No generic fallback may claim a physical card identity.

Decorative native navigation and disclosure icons keep null descriptions when adjacent text already names the action. CardArtworkHero uses `artwork_generic` for the generic illustration and a validated source-provided label for loaded artwork. CardArtworkSource, reference/revision, bounds, errors and stale-load cancellation remain intact. CardKey, NFC, USB, FIDO2 / PIV, cryptographic algorithm names and protocol extension names retain standard spelling. Enum labels appear only in advanced technical controls; their wire codes remain unchanged.

| Source | Before / after literal source | Decision / activation |
|---|---|---|
| `app/src/main/java/cc/cardkey/MainActivity.kt` | Before: `dismissButton = { TextButton(onClick = { showProviderSettings = false }) { Text("取消") } },`<br>After: `dismissButton = { TextButton(onClick = { showProviderSettings = false }) { Text("取消") } },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/MainActivity.kt` | Before: `title = { Text("凭据提供者") },`<br>After: `title = { Text("凭据提供者") },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/MainActivity.kt` | Before: `}) { Text("启用") } },`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/MainActivity.kt` | Before: `[new]`<br>After: `}) { Text("打开设置") } },` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `Choice("槽位", state.selection.slot, PivClient.slots, { "%02X".format(it) }, !busy) {`<br>After: `Choice("槽位", state.selection.slot, PivClient.slots, { "%02X".format(it) }, !busy) {` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `Choice("算法", state.selection.algorithm, KeyAlgorithm.entries, { it.name }, !busy) {`<br>After: `Choice("算法", state.selection.algorithm, KeyAlgorithm.entries, { it.name }, !busy) {` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `OutlinedTextField(admin, { admin = it }, enabled = !busy, label = { Text("管理密钥") }, singleLine = true,`<br>After: `OutlinedTextField(admin, { admin = it }, enabled = !busy, label = { Text("管理密钥") }, singleLine = true,` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `OutlinedTextField(pin, { pin = it }, enabled = !busy, label = { Text("当前 PIV PIN") }, singleLine = true,`<br>After: `OutlinedTextField(pin, { pin = it }, enabled = !busy, label = { Text("当前 PIV PIN") }, singleLine = true,` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `Text("6–8 位数字", style = MaterialTheme.typography.bodyMedium)`<br>After: `Text("6–8 位数字", style = MaterialTheme.typography.bodyMedium)` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `confirmButton = { TextButton(onClick = { pending = null; operation.dispatch(onSubmit) }) { Text("确认") } },`<br>After: `confirmButton = { TextButton(onClick = { pending = null; operation.dispatch(onSubmit) }) { Text("确认") } },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `dismissButton = { TextButton(onClick = { operation.close(); pending = null }) { Text("取消") } })`<br>After: `dismissButton = { TextButton(onClick = { operation.close(); pending = null }) { Text("取消") } })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `if (busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-cancel")) { Text("取消") }`<br>After: `if (busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-cancel")) { Text("取消") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt` | Before: `modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-cancel")) { Text("取消") }`<br>After: `modifier = Modifier.heightIn(min = 48.dp).testTag("enrollment-cancel")) { Text("取消") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `(extensions["credBlob"] as? Cbor.Bool)?.let { ResultRow("credBlob", it.value.toString()) }, uvm?.let { rows -> ResultRow("用户验证方法", rows.joinToString("; ") { it.joinToString() }) })`<br>After: `(extensions["credBlob"] as? Cbor.Bool)?.let { ResultRow("credBlob", it.value.toString()) }, uvm?.let { rows -> ResultRow("用户验证方法", rows.joinToString("; ") { it.joinToString() }) })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_BLOB_READ("扩展数据 · 读取"), FIDO_BLOB_WRITE("扩展数据 · 设置"),`<br>After: `FIDO_BLOB_READ("扩展数据 · 读取"), FIDO_BLOB_WRITE("扩展数据 · 设置"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_CAPACITY("凭据 · 容量"), FIDO_RPS("RP · 读取"),`<br>After: `FIDO_CAPACITY("凭据 · 容量"), FIDO_RPS("RP · 读取"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_CREDENTIALS("凭据 · 读取"), FIDO_DELETE("凭据 · 删除"),`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `[new]`<br>After: `FIDO_CREDENTIALS("凭据 · 读取"), FIDO_DELETE("删除通行密钥"),` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_INFO("FIDO2 · 读取"), FIDO_RESET("FIDO2 · 重置"), FIDO_RETRIES("PIN · 重试次数"),`<br>After: `FIDO_INFO("FIDO2 · 读取"), FIDO_RESET("FIDO2 · 重置"), FIDO_RETRIES("PIN · 重试次数"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `[new]`<br>After: `FIDO_INVENTORY("查看通行密钥"),` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_MIN_PIN("PIN · 最短长度"), FIDO_ENTERPRISE("企业证明 · 启用"),`<br>After: `FIDO_MIN_PIN("PIN · 最短长度"), FIDO_ENTERPRISE("企业证明 · 启用"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_REGISTER("注册 · 验证"), FIDO_ASSERT("签名 · 验证"), FIDO_VERIFY_CREDENTIAL("验证签名"),`<br>After: `FIDO_REGISTER("注册 · 验证"), FIDO_ASSERT("签名 · 验证"), FIDO_VERIFY_CREDENTIAL("验证签名"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_SET_PIN("PIN · 设置"), FIDO_CHANGE_PIN("PIN · 修改"),`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `[new]`<br>After: `FIDO_SET_PIN("设置 FIDO PIN"), FIDO_CHANGE_PIN("更改 FIDO PIN"),` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `[new]`<br>After: `FIDO_UPDATE("修改用户名"), FIDO_ALWAYS_UV("始终验证用户"),` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `FIDO_UPDATE("凭据 · 修改"), FIDO_ALWAYS_UV("始终验证用户"),`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ManagerReport(listOf(ResultRow("PIN 剩余重试次数", "${it.pin} 次")) +`<br>After: `ManagerReport(listOf(ResultRow("PIN 剩余重试次数", "${it.pin} 次")) +` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ManagerReport(listOf(ResultRow("已存储凭据", "${it.existing} 个"),`<br>After: `ManagerReport(listOf(ResultRow("已存储凭据", "${it.existing} 个"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ManagerReport(listOf(ResultRow("支持的协议", client.version())))`<br>After: `ManagerReport(listOf(ResultRow("支持的协议", client.version())))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ManagerReport(listOf(ResultRow("版本", it.version), ResultRow("序列号", it.serial.toString()),`<br>After: `ManagerReport(listOf(ResultRow("版本", it.version), ResultRow("序列号", it.serial.toString()),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ManagerReport(listOf(ResultRow("证书", it.subjectX500Principal.name)))`<br>After: `ManagerReport(listOf(ResultRow("证书", it.subjectX500Principal.name)))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ManagerReport(listOfNotNull(it.algorithm?.let { algorithm -> ResultRow("算法", algorithm.name) },`<br>After: `ManagerReport(listOfNotNull(it.algorithm?.let { algorithm -> ResultRow("算法", algorithm.name) },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_CHANGE_PIN("PIN · 修改", true), PIV_CHANGE_PUK("PUK · 修改", true),`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `[new]`<br>After: `PIV_CHANGE_PIN("更改 PIV PIN", true), PIV_CHANGE_PUK("更改 PUK", true),` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_ECDH("ECDH · 验证", true), PIV_DECRYPT("RSA · 验证", true),`<br>After: `PIV_ECDH("ECDH · 验证", true), PIV_DECRYPT("RSA · 验证", true),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_ENROLL_CSR("生成 CSR", true), PIV_ENROLL_IMPORT("导入证书", true),`<br>After: `PIV_ENROLL_CSR("生成 CSR", true), PIV_ENROLL_IMPORT("导入证书", true),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_GENERATE("生成密钥", true), PIV_CSR("导出 CSR", true),`<br>After: `PIV_GENERATE("生成密钥", true), PIV_CSR("导出 CSR", true),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_IMPORT("导入证书", true), PIV_SIGN("文件签名", true), PIV_CMS_SIGN("CMS 文件签名", true),`<br>After: `PIV_IMPORT("导入证书", true), PIV_SIGN("文件签名", true), PIV_CMS_SIGN("CMS 文件签名", true),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_INFO("PIV · 读取", true), PIV_SLOT("证书 · 读取", true), PIV_SSH_EXPORT("导出 SSH 公钥", true),`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `[new]`<br>After: `PIV_INFO("查看 PIV 状态", true), PIV_SLOT("查看证书", true), PIV_SSH_EXPORT("导出 SSH 公钥", true),` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_RESET("PIV · 重置", true);`<br>After: `PIV_RESET("PIV · 重置", true);` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `PIV_UNBLOCK("PIN · 解锁", true), PIV_ADMIN("管理密钥 · 修改", true),`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `[new]`<br>After: `PIV_UNBLOCK("解锁 PIV PIN", true), PIV_ADMIN("管理密钥 · 修改", true),` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("", "请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。")`<br>After: `ResultRow("", "请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。")` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("PIN · 协议", info.pinProtocols.joinToString())) +`<br>After: `ResultRow("PIN · 协议", info.pinProtocols.joinToString())) +` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("PIN 剩余重试次数", "剩余 ${it.pin.remaining} 次（共 ${it.pin.total} 次）"),`<br>After: `ResultRow("PIN 剩余重试次数", "剩余 ${it.pin.remaining} 次（共 ${it.pin.total} 次）"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("PIN 验证策略", listOf("无需 PIN", "会话内验证一次", "每次操作验证")[it.pinPolicy - 1]),`<br>After: `ResultRow("PIN 验证策略", listOf("无需 PIN", "会话内验证一次", "每次操作验证")[it.pinPolicy - 1]),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("PUK 剩余重试次数", "剩余 ${it.puk.remaining} 次（共 ${it.puk.total} 次）"),`<br>After: `ResultRow("PUK 剩余重试次数", "剩余 ${it.puk.remaining} 次（共 ${it.puk.total} 次）"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("凭据 ID", request.credential.idHex)))`<br>After: `ResultRow("凭据 ID", request.credential.idHex)))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("到期时间（UTC）", Instant.ofEpochMilli(end).toString()),`<br>After: `ResultRow("到期时间（UTC）", Instant.ofEpochMilli(end).toString()),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("剩余凭据容量（估算）", "${it.remaining} 个")))`<br>After: `ResultRow("剩余凭据容量（估算）", "${it.remaining} 个")))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("名称", it.user.name.orEmpty()), ResultRow("显示名称", it.user.displayName.orEmpty()))`<br>After: `ResultRow("名称", it.user.name.orEmpty()), ResultRow("显示名称", it.user.displayName.orEmpty()))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("支持的扩展", info.extensions.joinToString().ifEmpty { "无" }),`<br>After: `ResultRow("支持的扩展", info.extensions.joinToString().ifEmpty { "无" }),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("检查时间（设备时钟，UTC）", Instant.ofEpochMilli(assessedAt).toString()))`<br>After: `ResultRow("检查时间（设备时钟，UTC）", Instant.ofEpochMilli(assessedAt).toString()))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("生效时间（UTC）", Instant.ofEpochMilli(start).toString()),`<br>After: `ResultRow("生效时间（UTC）", Instant.ofEpochMilli(start).toString()),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("签发者", cert.issuerX500Principal.name),`<br>After: `ResultRow("签发者", cert.issuerX500Principal.name),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("管理密钥", it.managementAlgorithm.name)))`<br>After: `ResultRow("管理密钥", it.managementAlgorithm.name)))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("触摸确认策略", listOf("无需触摸", "每次操作触摸", "允许缓存触摸确认")[it.touchPolicy - 1]),`<br>After: `ResultRow("触摸确认策略", listOf("无需触摸", "每次操作触摸", "允许缓存触摸确认")[it.touchPolicy - 1]),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("证书", cert?.subjectX500Principal?.name ?: "未存储证书"))`<br>After: `ResultRow("证书", cert?.subjectX500Principal?.name ?: "未存储证书"))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("证书序列号（十六进制）", cert.serialNumber.toString(16).uppercase(Locale.ROOT)),`<br>After: `ResultRow("证书序列号（十六进制）", cert.serialNumber.toString(16).uppercase(Locale.ROOT)),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `ResultRow("证明", proof.kind.name), ResultRow("计数", proof.counter.toString())) +`<br>After: `ResultRow("证明", proof.kind.name), ResultRow("计数", proof.counter.toString())) +` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `U2F_INFO("U2F · 读取"), U2F_REGISTER("U2F · 注册"), U2F_ASSERT("U2F · 验证"),`<br>After: `U2F_INFO("U2F · 读取"), U2F_REGISTER("U2F · 注册"), U2F_ASSERT("U2F · 验证"),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `assessedAt < start -> "尚未生效"`<br>After: `assessedAt < start -> "尚未生效"` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `assessedAt > end -> "已过期"`<br>After: `assessedAt > end -> "已过期"` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `else -> "有效期内"`<br>After: `else -> "有效期内"` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `extensions["minPinLength"]?.let { ResultRow("最短 PIN 长度", "${it.number()} 个字符") },`<br>After: `extensions["minPinLength"]?.let { ResultRow("最短 PIN 长度", "${it.number()} 个字符") },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `info.algorithms?.let { parameters -> ResultRow("算法", parameters.joinToString { parameter ->`<br>After: `info.algorithms?.let { parameters -> ResultRow("算法", parameters.joinToString { parameter ->` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `info.forcePinChange?.let { ResultRow("需要修改 PIN", if (it) "是" else "否") },`<br>After: `info.forcePinChange?.let { ResultRow("需要修改 PIN", if (it) "是" else "否") },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `info.options["alwaysUv"]?.let { ResultRow("始终验证", if (it) "开启" else "关闭") },`<br>After: `info.options["alwaysUv"]?.let { ResultRow("始终验证", if (it) "开启" else "关闭") },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `info.options["clientPin"]?.let { ResultRow("PIN", if (it) "已设置" else "未设置") },`<br>After: `info.options["clientPin"]?.let { ResultRow("PIN", if (it) "已设置" else "未设置") },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `info.options["ep"]?.let { ResultRow("企业证明", if (it) "开启" else "关闭") },`<br>After: `info.options["ep"]?.let { ResultRow("企业证明", if (it) "开启" else "关闭") },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `info.raw[13]?.let { ResultRow("最短 PIN 长度", "${it.number()} 个字符") }))`<br>After: `info.raw[13]?.let { ResultRow("最短 PIN 长度", "${it.number()} 个字符") }))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `it.publicKey?.let { key -> ResultRow("公钥", Base64.getEncoder().encodeToString(key.encoded)) }))`<br>After: `it.publicKey?.let { key -> ResultRow("公钥", Base64.getEncoder().encodeToString(key.encoded)) }))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `listOf("无需 PIN", "会话内验证一次", "每次操作验证")[it.pinPolicy - 1]) },`<br>After: `listOf("无需 PIN", "会话内验证一次", "每次操作验证")[it.pinPolicy - 1]) },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `listOf("无需触摸", "每次操作触摸", "允许缓存触摸确认")[it.touchPolicy - 1]) },`<br>After: `listOf("无需触摸", "每次操作触摸", "允许缓存触摸确认")[it.touchPolicy - 1]) },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `listOf(ResultRow("凭据 ID", it.id.hex()), ResultRow("用户 ID", it.user.id.hex()),`<br>After: `listOf(ResultRow("凭据 ID", it.id.hex()), ResultRow("用户 ID", it.user.id.hex()),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `listOf(ResultRow("支持的协议", info.versions.joinToString()),`<br>After: `listOf(ResultRow("支持的协议", info.versions.joinToString()),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `listOfNotNull(it.uv?.let { count -> ResultRow("UV 剩余重试次数", "$count 次") },`<br>After: `listOfNotNull(it.uv?.let { count -> ResultRow("UV 剩余重试次数", "$count 次") },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `request?.credential?.let { ResultRow("凭据 ID", it.idHex) },`<br>After: `request?.credential?.let { ResultRow("凭据 ID", it.idHex) },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `return ManagerReport(listOf(ResultRow("RP", "cardkey.example"), ResultRow("凭据 ID", result.keyHandle.hex())))`<br>After: `return ManagerReport(listOf(ResultRow("RP", "cardkey.example"), ResultRow("凭据 ID", result.keyHandle.hex())))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `return ManagerReport(listOf(ResultRow("RP", "cardkey.example"), ResultRow("计数", result.counter.toString())))`<br>After: `return ManagerReport(listOf(ResultRow("RP", "cardkey.example"), ResultRow("计数", result.counter.toString())))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("凭据 ID", proof.credentialId.hex()),`<br>After: `return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("凭据 ID", proof.credentialId.hex()),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("计数", verified.counter.toString())) +`<br>After: `return ManagerReport(listOf(ResultRow("RP", rpId), ResultRow("计数", verified.counter.toString())) +` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `rows += listOf(ResultRow("日期状态", status),`<br>After: `rows += listOf(ResultRow("日期状态", status),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `rows += listOfNotNull(slot.metadata?.algorithm?.let { ResultRow("算法", it.name) },`<br>After: `rows += listOfNotNull(slot.metadata?.algorithm?.let { ResultRow("算法", it.name) },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `slot.metadata?.let { ResultRow("PIN 验证策略",`<br>After: `slot.metadata?.let { ResultRow("PIN 验证策略",` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `slot.metadata?.let { ResultRow("触摸确认策略",`<br>After: `slot.metadata?.let { ResultRow("触摸确认策略",` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `slot.metadata?.publicKey?.let { ResultRow("公钥", Base64.getEncoder().encodeToString(it.encoded)) })`<br>After: `slot.metadata?.publicKey?.let { ResultRow("公钥", Base64.getEncoder().encodeToString(it.encoded)) })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `start > end -> "有效期起止顺序异常"`<br>After: `start > end -> "有效期起止顺序异常"` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerOperation.kt` | Before: `val rows = mutableListOf(ResultRow("槽位", "%02X".format(slot.slot)),`<br>After: `val rows = mutableListOf(ResultRow("槽位", "%02X".format(slot.slot)),` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `"credProtect", "credBlob", "用户验证方法")`<br>After: `"credProtect", "credBlob", "用户验证方法")` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `"支持的协议", "支持的扩展", "PIN · 协议", "算法", "版本", "序列号",`<br>After: `"支持的协议", "支持的扩展", "PIN · 协议", "算法", "版本", "序列号",` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `(!op.isFileSigning \|\| (signingFiles.selection != null && !state.nfc && state.selectedReader?.wire == CardWire.APDU))) { Text(if (op == ManagerOperation.FIDO_INVENTORY) "继续" else op.title.substringAfter("·").trim()) }` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `.replace("{当前验证项}", current).replace("{另一项}", other)) },`<br>After: `.replace("{当前验证项}", current).replace("{另一项}", other)) },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Choice("PIN 验证策略", pinPolicy, PinPolicy.entries, { it.name }, !state.busy) { pinPolicy = it }`<br>After: `Choice("PIN 验证策略", pinPolicy, PinPolicy.entries, { it.name }, !state.busy) { pinPolicy = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Choice("新管理密钥 · 算法", newAdminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { newAdminAlgorithm = it }`<br>After: `Choice("新管理密钥 · 算法", newAdminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { newAdminAlgorithm = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Choice("槽位", slot, PivClient.slots, { "%02X".format(it) }, !state.busy) { slot = it }`<br>After: `Choice("槽位", slot, PivClient.slots, { "%02X".format(it) }, !state.busy) { slot = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Choice("算法", algorithm, KeyAlgorithm.entries, { it.name }, !state.busy) { algorithm = it }`<br>After: `Choice("算法", algorithm, KeyAlgorithm.entries, { it.name }, !state.busy) { algorithm = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Choice("管理密钥 · 算法", adminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { adminAlgorithm = it }`<br>After: `Choice("管理密钥 · 算法", adminAlgorithm, ManagementAlgorithm.entries, { it.name }, !state.busy) { adminAlgorithm = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `ManagerOperation.FIDO_MIN_PIN -> "PIN · 最短长度"`<br>After: `ManagerOperation.FIDO_MIN_PIN -> "PIN · 最短长度"` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `OutlinedButton(onClick = onImport, enabled = !state.busy) { Text("导入证书") }`<br>After: `OutlinedButton(onClick = onImport, enabled = !state.busy) { Text("导入证书") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新读卡器") }`<br>After: `OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新读卡器") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `OutlinedButton(onClick = { advanced = true; objectDetail = true; tab = 0; operation = ManagerOperation.FIDO_INFO }, enabled = !state.busy) { Text("高级 FIDO 设置与诊断") }` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `OutlinedButton(onClick = { advanced = true; objectDetail = true; tab = 1; operation = ManagerOperation.PIV_INFO }, enabled = !state.busy && state.supportsPiv) { Text("高级 PIV 管理与诊断") }` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `OutlinedButton(onClick = { if (inventory.credential != null) onInventoryFile(inventory.generation) else onImport() }, enabled = !state.busy) { Text("导入") }`<br>After: `OutlinedButton(onClick = { if (inventory.credential != null) onInventoryFile(inventory.generation) else onImport() }, enabled = !state.busy) { Text("导入") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `OutlinedTextField(secondary, { secondary = it }, label = { Text("名称") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `OutlinedTextField(secondary, { secondary = it }, label = { Text("新用户名") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().workspaceInput(viewportHeight))` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `PlexoFactRow("凭据 ID", credential.idHex)`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `PlexoFactRow("账户", credential.name ?: credential.displayName ?: "未命名账户")` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Text("$it 字节", style = MaterialTheme.typography.bodySmall)`<br>After: `Text("$it 字节", style = MaterialTheme.typography.bodySmall)` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Text("扩展", Modifier.weight(1f))`<br>After: `Text("扩展", Modifier.weight(1f))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Text("操作", style = MaterialTheme.typography.labelLarge)`<br>After: `Text("操作", style = MaterialTheme.typography.labelLarge)` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Text(if (name == "uvm") "用户验证方法" else name)`<br>After: `Text(if (name == "uvm") "用户验证方法" else name)` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Text(if (state.nfc) "NFC" else state.selectedReader?.device?.productName ?: "读卡器",`<br>After: `Text(if (state.nfc) "NFC" else state.selectedReader?.device?.productName ?: "读卡器",` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `Text(when (op) { ManagerOperation.FIDO_ALWAYS_UV -> "始终验证用户"; ManagerOperation.FIDO_MIN_PIN -> "修改 PIN"; else -> "驻留凭据" }, Modifier.weight(1f))`<br>After: `Text(when (op) { ManagerOperation.FIDO_ALWAYS_UV -> "始终验证用户"; ManagerOperation.FIDO_MIN_PIN -> "修改 PIN"; else -> "驻留凭据" }, Modifier.weight(1f))` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } })`<br>After: `confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `confirmButton = { TextButton(onClick = { exportConfirmation = null; onExport(pending) }) { Text("确认") } },`<br>After: `confirmButton = { TextButton(onClick = { exportConfirmation = null; onExport(pending) }) { Text("确认") } },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `confirmButton = { TextButton(onClick = { onLastRetry(true) }) { Text("确认") } },`<br>After: `confirmButton = { TextButton(onClick = { onLastRetry(true) }) { Text("确认") } },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `confirmButton = { TextButton(onClick = { onLastRetry(true) }) { Text("验证一次") } },` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `confirmButton = { TextButton(onClick = { showSecretConfirmation = false; showSecrets = true }) { Text("确认") } },`<br>After: `confirmButton = { TextButton(onClick = { showSecretConfirmation = false; showSecrets = true }) { Text("确认") } },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `credential.displayName?.let { PlexoFactRow("名称", it) }`<br>After: `credential.displayName?.let { PlexoFactRow("名称", it) }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })`<br>After: `dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `dismissButton = { TextButton(onClick = { exportConfirmation = null }) { Text("取消") } }) }`<br>After: `dismissButton = { TextButton(onClick = { exportConfirmation = null }) { Text("取消") } }) }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `dismissButton = { TextButton(onClick = { onLastRetry(false) }) { Text("取消") } })`<br>After: `dismissButton = { TextButton(onClick = { onLastRetry(false) }) { Text("取消") } })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `dismissButton = { TextButton(onClick = { showSecretConfirmation = false }) { Text("取消") } })`<br>After: `dismissButton = { TextButton(onClick = { showSecretConfirmation = false }) { Text("取消") } })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `else -> "主题名称"` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `else -> "凭据 ID · HEX"`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (inventory.credentials.isNotEmpty()) Choice("凭据 ID", inventory.credential,`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (pending.inventoryGeneration != null) pending.rows.filter { it.label in setOf("RP", "凭据 ID") }`<br>After: `if (pending.inventoryGeneration != null) pending.rows.filter { it.label in setOf("RP", "凭据 ID") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `if (secretInputReady && needsPin(op)) SecretField(when { op == ManagerOperation.PIV_CHANGE_PUK \|\| op == ManagerOperation.PIV_UNBLOCK -> "当前 PUK"; op.piv -> "当前 PIV PIN"; else -> "FIDO PIN" }, pin, !state.busy, viewportHeight) { pin = it }` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (secretInputReady && needsPin(op)) SecretField(when { op == ManagerOperation.PIV_CHANGE_PUK \|\| op == ManagerOperation.PIV_UNBLOCK -> "当前 PUK"; op.piv -> "当前 PIV PIN"; else -> "当前 FIDO PIN" }, pin, !state.busy, viewportHeight) { pin = it }`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (secretInputReady && op.piv && needsPin(op)) Text("6–8 位数字", style = MaterialTheme.typography.bodySmall)`<br>After: `if (secretInputReady && op.piv && needsPin(op)) Text("6–8 位数字", style = MaterialTheme.typography.bodySmall)` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (secretInputReady) SecretField("新管理密钥 · HEX", newAdminKey, !state.busy, viewportHeight) { newAdminKey = it }`<br>After: `if (secretInputReady) SecretField("新管理密钥 · HEX", newAdminKey, !state.busy, viewportHeight) { newAdminKey = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (secretInputReady) SecretField("管理密钥 · HEX", adminKey, !state.busy, viewportHeight) { adminKey = it }`<br>After: `if (secretInputReady) SecretField("管理密钥 · HEX", adminKey, !state.busy, viewportHeight) { adminKey = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (secretInputReady) SecretField(when { op == ManagerOperation.PIV_CHANGE_PUK -> "新 PUK"; op.piv -> "新 PIV PIN"; else -> "新 FIDO PIN" }, newPin, !state.busy, viewportHeight) { newPin = it }`<br>After: `if (secretInputReady) SecretField(when { op == ManagerOperation.PIV_CHANGE_PUK -> "新 PUK"; op.piv -> "新 PIV PIN"; else -> "新 FIDO PIN" }, newPin, !state.busy, viewportHeight) { newPin = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }`<br>After: `if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `if (tab == 3 && state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }`<br>After: `if (tab == 3 && state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `input.inventorySelection?.let { Text("RP · ${it.rp.id}"); Text("凭据 ID · ${it.idHex}") }`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `input.inventorySelection?.let { Text("网站 · ${it.rp.id}"); Text("账户 · ${it.name ?: it.displayName ?: "未命名账户"}") }` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `inventory.credentials, { it?.idHex ?: "凭据 ID" }, !state.busy) { it?.let { credential -> onInventoryCredential(inventory.generation, credential) } }`<br>After: `[removed]` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `listOf(0, 1, 2), { when (it) { 1 -> "厂商预设（1）"; 2 -> "平台管理（2）"; else -> "不请求" } }, !state.busy) { enterpriseMode = it }`<br>After: `listOf(0, 1, 2), { when (it) { 1 -> "厂商预设（1）"; 2 -> "平台管理（2）"; else -> "不请求" } }, !state.busy) { enterpriseMode = it }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `[new]`<br>After: `title = { Text("仅剩一次 PIN 尝试") },` | Applied manager task copy/removal; no provider flow activation. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `title = { Text("导出") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {`<br>After: `title = { Text("导出") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `val technicalLabels = setOf("公钥", "RP SHA-256", "用户 ID", "证明", "计数",`<br>After: `val technicalLabels = setOf("公钥", "RP SHA-256", "用户 ID", "证明", "计数",` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `}) { Text("确认") } },`<br>After: `}) { Text("确认") } },` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `}, enabled = !state.busy) { Text("读卡器") }`<br>After: `}, enabled = !state.busy) { Text("读卡器") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/ManagerScreen.kt` | Before: `}, enabled = (!result.value.sshPublicKey && result.value.signatureFormat == null) \|\| signingFiles.exportStatus != SignatureExportStatus.WRITING) { Text("导出") }`<br>After: `}, enabled = (!result.value.sshPublicKey && result.value.signatureFormat == null) \|\| signingFiles.exportStatus != SignatureExportStatus.WRITING) { Text("导出") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/PlexoWorkspace.kt` | Before: `if (ambiguous && title != credential.idHex) add("凭据 ID · ${credential.idHex}")`<br>After: `if (ambiguous && title != credential.idHex) add("凭据 ID · ${credential.idHex}")` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt` | Before: `OutlinedTextField(pin, { pin = it }, enabled = !busy, label = { Text("当前 PIV PIN") }, singleLine = true,`<br>After: `OutlinedTextField(pin, { pin = it }, enabled = !busy, label = { Text("当前 PIV PIN") }, singleLine = true,` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt` | Before: `Text("6–8 位数字", style = MaterialTheme.typography.bodyMedium)`<br>After: `Text("6–8 位数字", style = MaterialTheme.typography.bodyMedium)` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt` | Before: `dismissButton = { TextButton(onClick = { input.close(); pending = null }) { Text("取消") } })`<br>After: `dismissButton = { TextButton(onClick = { input.close(); pending = null }) { Text("取消") } })` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt` | Before: `onClick = { pin = ""; pending?.close(); pending = null; onCancel() }) { Text("取消") }`<br>After: `onClick = { pin = ""; pending?.close(); pending = null; onCancel() }) { Text("取消") }` | Retained precise authored wording in its task context. |
| `app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt` | Before: `}) { Text("确认") } },`<br>After: `}) { Text("确认") } },` | Retained precise authored wording in its task context. |

## Copy kept for its real consequence

Retained: irreversible credential loss; PIN/PUK change and PIV erasure conditions; management-key replacement; private-key overwrite; certificate replacement and public-key match; secret/decrypted export; malformed card/transport errors; unknown or accepted-but-unread writes; contact-only PIV restrictions; fresh user-presence instructions; certificate date versus trust distinctions; CSR/CA verification versus card import; TLS/server/account acceptance boundaries; file-signature verification versus trust and original-file retention.

PIN setup and forced-change results direct users to the named separate task, with no automatic reuse of an entered current PIN as setup consent. A complete successful empty enumeration is distinguished from unread, loading and failed states. Unknown writes retain the existing mutation fence. Document outcomes never imply a card mutation succeeded or failed.

## Provider review at the manager baseline

The provider-specific resource rows and ProviderActivity literal rows were reviewed in the existing verified-request → reader → recognition → conditional PIN → explicit consent → result flow. At that checkpoint, provider consumer work was deferred while retaining verified caller/origin, claim reservation, secure-window input, card user presence, account selection, cancellation and response ownership. The linked provider review now owns the subsequent flow and copy decisions. The manager checkpoint deliberately left the recognize-button behavior unchanged. The linked provider consumer review records its subsequent replacement and activation. Shared error labels do not by themselves authorize provider activation or protocol changes.

## Verification adjudication

The first broad run exposed 43 failed cases: two CA fixture environment paths, old UI assertions requiring manual recognition or former navigation labels, mutation fixtures supplying raw IDs without an owned selection, and real regressions in the new presentation. The repairs retain the actual permission, geometry, process-death, captured-export, crypto/readback and cancellation checks. Raw-file signing stays a normal certificate task. Ambiguous account rows retain complete identifiers so identical names cannot select the wrong credential. Advanced settings now open the task detail in compact layouts. Passkey navigation opens the PIN task directly; certificates still open their object list.

Tests asserting manual pre-read as a prerequisite were rewritten to assert secure input preparation without card I/O, secret retirement on route/epoch/permission changes, and disabled execution while permission is pending. Existing signature/blob picker tests still require exact captured bytes, one owned callback, process-death rejection and no automatic replay. No provider tests or workflow were removed. Final build/test results are recorded with the candidate, separately from installation and hardware acceptance.

## Final delta from the full-suite checkpoint

The full-suite checkpoint passed 868 tests with zero failures/errors and one existing skip. The subsequent delta removes proven unused reminder resources, updates the instrumentation acceptance fixture for the PIN-first default, fixes diagnostic-list indentation with braces, and adds precise mutation completion plus an explicit new-read task without automatic authentication. It also distinguishes known cancellation from unknown effects in visible copy. Relevant confirmation, cold-start/post-mutation UI, geometry, inventory lifecycle/final-attempt and protocol tests cover this delta; unrelated passed CA/provider evidence is reused. Instrumentation code is compiled, not executed on a device.

The final consistency correction removes `confirm_authenticate` and `confirm_pinChange` after tracing every runtime, XML and test reference. Explicit named task submission owns ordinary PIN, PUK, rename and change consent. Conditional PIV key-erasure/final-retry checks and FIDO final-attempt consent remain; admin-key, policy, reset, credential-loss, blob-overwrite and secret-export consequences remain. Lifecycle dialog tests now exercise actual policy/secret consequences rather than a generic incorrect-PIN reminder.

Final verification evidence: the initial complete suite passed 868 tests (zero failures/errors, one existing skip); the subsequent mutation/copy/instrumentation delta passed 69 relevant tests and build/lint; the Owner-directed generic-consent removal passed 90 relevant safety/lifecycle tests and build/lint. The final resource-only removal of the lint-proven unused old credential heading requires compilation/lint only. No test floor, lint suppression or provider behavioral code was changed.

Fresh inventory power-cycle recovery follows the actual transport: APDU/NFC asks for removal and reconnection; direct CTAP keeps the existing all-power-removal instruction. Both stop before PIN authentication. Legacy provider/status mappings are unchanged.

The bounded power-cycle correction passed 51 relevant protocol/confirmation/inventory-lifecycle tests with zero failures/errors/skips, plus APK and instrumentation compilation and lint (0 errors, 10 retained protocol/dependency warnings).


### Signing-panel regression correction

The first remote CI run executed 872 tests and found four signing-panel fixtures still expecting the removed routine PIN confirmation. The runtime had already adopted explicit task submission. The fixtures now verify one submission of the captured file generation/slot/format/PIN after the named action, with no duplicate dialog. Background retirement is tested before submission and still requires an empty secure field, zero dispatches and a reachable IME-constrained scroll area. Existing unknown-export, card-recovery and CMS trust assertions remain.

The relevant signing-panel, signing-activity and confirmation suites passed all 49 tests in the bounded local rerun (28 seconds). This correction changes tests and this evidence record only; application source and the preserved debug APK are unchanged. Remote CI must pass on the final candidate before merge.

### Selected credential and deletion target correction

Reviewed with `plain-language-iso-24495` for the manager's existing Chinese vocabulary. Activation is limited to the manager presentation in this candidate; provider behavior, card mutation, PIN, permission, cancellation and generation gates remain unchanged.

| Screen/key | Before | After | Reason and activation |
| --- | --- | --- | --- |
| Manager / selected passkey detail | `RP`, optional `名称`, `账户`; no credential identifier | Existing rows plus `凭据 ID` with the selected credential's complete `idHex` | Match the selected list item even when account names collide or are absent. Visible for selected passkeys; no manual ID entry. |
| Manager / final `FIDO_DELETE` confirmation | Irreversible-loss warning without website/account/credential target | `网站 · {rp.id}`, `账户 · {name or displayName or 未命名账户}`, `凭据 ID · {idHex}`, followed by the unchanged warning | Identify the exact deletion target before explicit `确认`. All three fields come from immutable `input.inventorySelection`, never the current selection. |
| Manager / selected proof and blob confirmation | `网站 · {rp.id}`, `账户 · {name or displayName or 未命名账户}` | Existing fields plus `凭据 ID · {idHex}` | Use the same complete identifier as selected detail and deletion confirmation. Existing confirmation conditions remain unchanged. |

The complete identifier is retained for every selected credential, so disambiguation does not depend on a mutable list or a shortened-ID collision rule. Compact Compose regressions cover colliding names and unnamed accounts, visible detail and final-confirmation IDs, zero submissions before explicit confirmation, and dispatch of the original RP/account/ID selection even if the presented selection changes while the dialog is open. Existing inventory lifecycle, cryptographic proof and confirmation-policy evidence is retained.

Bounded verification passed in 2 minutes 30 seconds: 71 targeted tests, zero failures/errors/skips (`InventoryPanelTest` 5, `InventoryProofTest` 12, `InventoryViewModelTest` 17, `InventoryActivityTest` 8, `ConfirmationPolicyTest` 29), application Kotlin and instrumentation Kotlin compilation, and debug lint (0 errors, 10 retained warnings). `git diff --check` passed. No device, emulator, card, NFC, reader, provider acceptance or deployment check was performed. Remote candidate gates remain root-owned.


## Complete Android baseline follow-up (issue #45)

Reviewed with plain-language-iso-24495 v1.3 against parent `94cf342724a2ca6c95e3e11027a00456aafa2ddc`. Source coverage includes all 343 unique resource keys (669 catalog entries: 343 default, 163 qualified Chinese, 163 English), 39 manager operation titles, manager home/navigation/settings/readers/forms/results, passkey inventory and target identification, certificate application and retained receipts, TLS, signing/document/SSH export, protected PIV, Credential Provider and system picker entries, artwork and accessibility labels. The 69 production Kotlin files include 269 Text call sites; these are inventory counts, not unique displayed-message counts. Conditions were traced through the state producers and consumers, not inferred from phrase searches. Qualified Chinese matches default Chinese. Existing partial English fallback remains the recorded translation scope.

Exception messages are mapped to authored resources; no raw exception-message pass-through was found. Reader/account/certificate/file/artwork data remains supplied data. No app-owned notification, Toast or Snackbar text path exists in the examined production source; platform permission, file chooser and credential exception text remains platform-owned. Source review does not establish installed-device, physical-card, external-account, screen-reader or native-render acceptance. Existing unchanged wording review is reused from the consumer, provider-consumer and UI ledgers above.

| Screen / key / condition | Before | After | Reason / activation |
| --- | --- | --- | --- |
| CA request/retained receipt / `enrollment_unknown` / issued call settles UNKNOWN | `未确认签发结果。请先向 CA 管理员核实，勿重复提交。` | `未收到签发结果。请先向 CA 管理员核实，勿重复提交。` | Name the missing result; preserve CA verification and no replay. Default catalog; next application build/install. |
| CA retained receipt / `plexo_ca_unknownRequest` | `CA 签发结果待核实` | `未收到签发结果的申请` | Identify the retained request. Default catalog; next application build/install. |
| TLS result / `tls_unknown` / failed connection after authentication signature returned | `未确认认证结果。请先核对服务器记录，勿重复提交。` | `未收到符合要求的服务器响应。请先核对服务器记录，勿重复提交。` | Preserve possible submitted authentication without claiming success or failure. Default catalog; next application build/install. |
| Owned export / `signing_exportUnknown` / output write failure or missing owned bytes | ZH `未确认导出结果。请检查所选文件。`; EN `The export outcome is unconfirmed. Check the selected file.` | ZH `未收到文件保存结果。请检查所选位置中的文件。`; EN `No save result was received. Check the file in the selected location.` | Identify the output location; preserve possible partial output. Default/qualified Chinese and English; next application build/install. |
| Generic file export / `document_unknown` / output write did not produce a saved result | `未确认文件保存结果。请检查所选位置，勿重复提交卡片操作。` | `未收到文件保存结果。请检查所选位置，勿重复提交卡片操作。` | Preserve missing save result and no repeated card operation. Default catalog; next application build/install. |
| Reader context / `workspace_inspection` / unknown effect or accepted write with failed readback | `结果待核实；请先读取卡片状态` | `请先读取卡片状态` | Keep next action beside the detailed effect receipt. Default catalog; next application build/install. |
| Manager/provider failure result / `error_cancelled` selection / cancellation with UNKNOWN effect or accepted write | Lost-result or accepted-write notice followed by ZH `操作已取消。` / EN `The operation was canceled.` | Keep the precise lost-result or accepted-write notice; omit the generic cancellation suffix in those branches. NONE/not-dispatched cancellation retains its existing text. | Avoid claiming the card operation was canceled after it may have applied. Shared presentation only; all operation/recovery/PIN/transport state is unchanged. Next application build/install. |
| Protected recovery / `protectedPiv_inspection` / protected inspection fence | ZH `上次操作结果未知。请重新导入原卡片的配置，再读取受保护状态；不要重复执行原操作。`; EN `The previous operation outcome is unknown. Import the original card configuration again, then read its protected status. Do not repeat the original operation.` | ZH `请重新导入原卡片的配置，再读取受保护状态；读取前不要重复执行原操作。`; EN `Import the original card configuration again, then read its protected status. Do not repeat the original operation before reading the status.` | Keep exact recovery and no replay; detailed receipt retains the accepted-write versus lost-result distinction. Default/qualified Chinese and English; next application build/install. |
| Protected PIN status / `protectedPiv_unverified` / STATUS returns false verified bit without contact recovery | ZH `尚未验证`; EN `Not verified` | ZH `本次会话未验证 PIN`; EN `The PIN has not been verified in this session` | State the actual PIN/session fact from PivClient status mapping; do not assert all slots require PIN. Default/qualified Chinese and English; next application build/install. |

The protected-PIV risk/recovery keys retain their necessary meaning: original-profile inspection/no replay, original card/connection guidance, PIN length/attempt consequences and connection-loss uncertainty. Irreversible deletion/reset/key overwrite/PUK-dependent erasure, exact selected credential identity, private output disclosure, blob data loss, profile clearing and the Owner-restored username/display-name instruction remain. No new controls, card operations, retries, translation scope or provider activation were added.


Targeted validation: the first composed test/build run executed 78 tests in nine suites; 75 passed and three old-wording assertions failed (one CA receipt, two signing export cases). The new manager/provider NONE/UNKNOWN/accepted-write cancellation regressions and unchanged safety conditions passed. Only the old-copy assertions were updated; an additional matching export assertion in the signing Activity suite was corrected. The affected CA/signing rerun passed all 27 tests in three suites, zero failures/errors/skips (19.243s summed test time; Gradle 1m25s). Reusing the 66 passing tests from the other seven suites gives 93 unique final passing tests across ten suites. No full-suite run is claimed. The final English-only simplification to `No save result was received. Check the file in the selected location.` passed application/instrumentation APK packaging and lint in 59s; passed semantic evidence was reused.

Actual suites: ConfirmationPolicyTest (30), ConsumerManagerTest (5), PlexoProviderTest (11), ProtectedPivLocalizationTest (7), ProtectedPivLocaleListTest (6), CopyPruningTest (4), TlsPanelTest (3), EnrollmentPanelTest (6), PivFileSigningPanelTest (6), PivFileSigningActivityTest (15). Lint: zero errors, eleven warnings at unchanged locations (one English PluralsCandidate for protectedPiv_rowRetries, one GetInstance and nine dependency TrustAllX509TrustManager warnings). No suppressions, baselines, test floors, assertions about operation ownership, recovery state or PIN policy were weakened. Both APKs built with the established offline JDK17/SDK36/Gradle8.13 toolchain and max two workers. APK signer verification and final candidate identity are recorded in the root-owned local delivery evidence. No installed build, device, emulator, provider activation, physical card or external-account acceptance was performed.


### PR 49 recovery-guidance assertion correction

The review identified two remaining test callers that still selected the former protected recovery wording. Both positive and negative checks in ProtectedPivPanelTest and ManagerScreenLayoutTest now select the current exact instruction. Their recovery-mode distinction, original-card guidance, forced STATUS selection, enabled recovery controls and absence of PIN/replay remain asserted. A parsed sweep of all changed resource values against parent `94cf342724a2ca6c95e3e11027a00456aafa2ddc` found no remaining old-value references under app/src/test or app/src/androidTest after this correction. No application, resource, instrumentation, card operation or recovery-state source changed in this review round.

The affected suites passed all 28 cases (ProtectedPivPanelTest 8, ManagerScreenLayoutTest 20), zero failures/errors/skips; Gradle 52 seconds. The required complete JVM gate follows this test-only correction; its result is recorded below. The already signed production and instrumentation APKs remain the exact unchanged application/instrumentation payloads; rebuilding them would not validate these host-only assertion changes.


The complete `testDebugUnitTest --offline --no-daemon --no-watch-fs --max-workers=2 -Pkotlin.incremental=false` gate passed in Gradle 1m09s: 975 cases across 95 suites, 974 passed, zero failures/errors, one existing optional skip (`PivSshPublicKeyTest.sshKeygenInteroperabilityExplicitGate`, CARDKEY_SSH_KEYGEN unset as in CI). Summed test execution was 61.073 seconds. The pinned Smallstep CLI 0.31.0 and step-ca 0.30.2 inputs enabled the required loopback-CA tests. No new skip, removed assertion or weakened test was introduced. This result replaces the earlier targeted-only boundary for JVM tests; APK/lint and device acceptance boundaries remain as recorded above.


## Issuer record reader — 2026-10-26

Candidate: `feat/issuer-badge-reader-20261026`, stacked on Android CA settings `1df455f` (PR 53). Owning delivery: issuer issue 3; hub issues 35, 37 and 39. This increment reuses the complete manager/resource baseline above and subsequent native-workspace records. It reviews every added string in `values/issuer.xml`, all dynamic labels, selection conditions and image accessibility labels. The original issuer increment provided default Chinese strings. The PR 54 review below supplies the maintained English and explicit Chinese locale resources.

The home action opens an operator's server-record reader. Current personnel fields and publication-time fields have separate headings. Selecting a server record never claims a connected or authenticated physical card. Employee/card/history controls perform reads only. Connection edits immediately retire the prior identity, record and images. A 401 returns the operator to connection entry; saved CA settings and unresolved enrollment receipts remain owned by the existing enrollment workflow.

The password field names the operator token, distinguishes its service-wide authority, and states endpoint-scoped storage and blank-field reuse. Normal failures name the failed read, credential or redirect and an available action; raw server error bodies and protocol status names are not displayed. Server-owned person names, serials, timestamps and accessibility labels remain data. Missing publication and missing records have distinct display conditions.

| Resource key | Before | After | Trigger / review reason |
|---|---|---|---|
| `issuer_title` | Absent | 工卡记录 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_intro` | Absent | 连接工卡服务，选择员工并查看已发布的卡面。 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_address` | Absent | 工卡服务地址（HTTPS） | Connection form or authenticated session; preserve destination and operator authority. |
| `issuer_token` | Absent | 操作员访问令牌 | Connection form or authenticated session; preserve destination and operator authority. |
| `issuer_token_hint` | Absent | 令牌保存在此设备，仅用于对应服务。留空可使用已保存的令牌。操作员令牌也具有服务端管理权限。 | Connection form or authenticated session; preserve destination and operator authority. |
| `issuer_connect` | Absent | 连接并刷新 | Connection form or authenticated session; preserve destination and operator authority. |
| `issuer_disconnect` | Absent | 断开连接 | Connection form or authenticated session; preserve destination and operator authority. |
| `issuer_operator` | Absent | 操作员：%1$s | Connection form or authenticated session; preserve destination and operator authority. |
| `issuer_people` | Absent | 选择员工 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_no_people` | Absent | 服务中没有员工记录。 | Completed successful empty read; hidden while loading or after an error. |
| `issuer_more_people` | Absent | 更多员工 | Only when the server returns a next cursor; explicit bounded page read. |
| `issuer_person_current` | Absent | 当前员工资料 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_active` | Absent | 在职 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_inactive` | Absent | 已停用 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_no_cards` | Absent | 此员工没有工卡记录。 | Completed successful empty read; hidden while loading or after an error. |
| `issuer_more_cards` | Absent | 更多工卡 | Only when the server returns a next cursor; explicit bounded page read. |
| `issuer_card` | Absent | 工卡 %1$s · %2$s | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_issued` | Absent | 已登记 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_lost` | Absent | 已挂失 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_revoked` | Absent | 已撤销 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_other_status` | Absent | 其他状态 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_no_badge` | Absent | 此工卡还没有发布的卡面。 | Completed successful empty read; hidden while loading or after an error. |
| `issuer_current` | Absent | 当前卡面 · %1$s | Selected immutable publication; separate current server fields from historical snapshot. |
| `issuer_history` | Absent | 历史卡面 · %1$s | Selected immutable publication; separate current server fields from historical snapshot. |
| `issuer_more_history` | Absent | 更多历史卡面 | Only when the server returns a next cursor; explicit bounded page read. |
| `issuer_snapshot` | Absent | 卡面发布时的资料 | Selected immutable publication; separate current server fields from historical snapshot. |
| `issuer_published` | Absent | 发布时间：%1$s / 发布操作员：%2$s | Selected immutable publication; separate current server fields from historical snapshot. |
| `issuer_archived` | Absent | 卡面存档时间：%1$s | Selected immutable publication; separate current server fields from historical snapshot. |
| `issuer_front` | Absent | 正面 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_back` | Absent | 背面 | Relevant record selection or metadata field; ordinary task vocabulary. |
| `issuer_address_error` | Absent | 请输入 HTTPS 服务地址，不含路径、查询参数或登录信息。 | Relevant failure only; concrete event and next action. |
| `issuer_token_required` | Absent | 请输入此服务的操作员访问令牌。 | Relevant failure only; concrete event and next action. |
| `issuer_unauthorized` | Absent | 服务拒绝了访问令牌。请输入有效令牌后重新连接。 | Relevant failure only; concrete event and next action. |
| `issuer_missing` | Absent | 服务中已没有所选记录。请重新连接并选择记录。 | Relevant failure only; concrete event and next action. |
| `issuer_redirect` | Absent | 服务返回了跳转地址。请核对服务地址后重新连接。 | Relevant failure only; concrete event and next action. |
| `issuer_invalid_response` | Absent | 服务返回的记录或图片格式有误。请联系服务管理员。 | Relevant failure only; concrete event and next action. |
| `issuer_failed` | Absent | 读取服务失败。请检查网络后重试。 | Relevant failure only; concrete event and next action. |
| `issuer_secret_read_failed` | Absent | 此设备保存的访问令牌读取失败。请重新输入令牌。 | Relevant failure only; concrete event and next action. |
| `issuer_secret_save_failed` | Absent | 访问令牌保存失败。请重试连接。 | Relevant failure only; concrete event and next action. |

Dynamic coverage: person controls show server display name and employee reference; the selected personnel summary shows department, title and badge number only when nonempty. Card controls show serial and a translated server-record status. Revision controls show current/history and server publication time. The selected revision shows its historical name/reference/department/title/badge number, publisher and source creation time. Front/back images retain the server accessibility labels and real image aspect ratio, including portrait output. Existing image loading/failure/retry strings are reused unchanged.

Native layout acceptance is recorded in the Android badge evidence handoff. Device installation, real issuer data and physical-card acceptance are separate root-owned gates.


## PR 54 review corrections — 2026-10-26

Candidate: append-only review fix on `feat/issuer-badge-reader-20261026`, following `30e900e`. Scope: findings 4378577424 and 4378577435 plus CI lint MissingTranslation errors. The operator input now requests Android password keyboard semantics and disables autocorrect. Its visible label and secret-storage policy are unchanged.

Cards and artwork history now retain completion and error state for their own queries. Only a successful corresponding read can show an empty-record message. Loading another people page cannot erase either query's failure. Each failed section keeps its concrete error and an explicit retry action; a successful retry clears only that query's error. Network-failure text now says to retry because both reconnect and section-specific retry are supported actions.

All 41 issuer strings were reviewed together in default Chinese, explicit `values-zh`, and `values-en`. Existing manager copy coverage is reused; this table covers every issuer locale addition and the three changed/new messages. Activation is the next installed candidate. English and Chinese preserve the same operator authority, endpoint binding, retry behavior, record status and historical timestamps. This does not claim that unrelated legacy default-only resources have been translated.

| Key | Before | Chinese (default and zh) | English | Condition |
|---|---|---|---|---|
| `issuer_title` | English and explicit Chinese missing | 工卡记录 | Badge records | Same display condition as the issuer baseline above |
| `issuer_intro` | English and explicit Chinese missing | 连接工卡服务，选择员工并查看已发布的卡面。 | Connect to the badge service, select an employee, and view published artwork. | Same display condition as the issuer baseline above |
| `issuer_address` | English and explicit Chinese missing | 工卡服务地址（HTTPS） | Badge service address (HTTPS) | Same display condition as the issuer baseline above |
| `issuer_token` | English and explicit Chinese missing | 操作员访问令牌 | Operator access token | Same display condition as the issuer baseline above |
| `issuer_token_hint` | English and explicit Chinese missing | 令牌保存在此设备，仅用于对应服务。留空可使用已保存的令牌。操作员令牌也具有服务端管理权限。 | The token is saved on this device for this service only. Leave blank to use the saved token. Operator tokens also allow changes on the service. | Same display condition as the issuer baseline above |
| `issuer_connect` | English and explicit Chinese missing | 连接并刷新 | Connect and refresh | Same display condition as the issuer baseline above |
| `issuer_disconnect` | English and explicit Chinese missing | 断开连接 | Disconnect | Same display condition as the issuer baseline above |
| `issuer_operator` | English and explicit Chinese missing | 操作员：%1$s | Operator: %1$s | Same display condition as the issuer baseline above |
| `issuer_people` | English and explicit Chinese missing | 选择员工 | Select an employee | Same display condition as the issuer baseline above |
| `issuer_no_people` | English and explicit Chinese missing | 服务中没有员工记录。 | The service has no employee records. | Same display condition as the issuer baseline above |
| `issuer_more_people` | English and explicit Chinese missing | 更多员工 | More employees | Same display condition as the issuer baseline above |
| `issuer_person_current` | English and explicit Chinese missing | 当前员工资料 | Current employee details | Same display condition as the issuer baseline above |
| `issuer_active` | English and explicit Chinese missing | 在职 | Active | Same display condition as the issuer baseline above |
| `issuer_inactive` | English and explicit Chinese missing | 已停用 | Inactive | Same display condition as the issuer baseline above |
| `issuer_no_cards` | English and explicit Chinese missing | 此员工没有工卡记录。 | This employee has no badge records. | Same display condition as the issuer baseline above |
| `issuer_more_cards` | English and explicit Chinese missing | 更多工卡 | More badges | Same display condition as the issuer baseline above |
| `issuer_card` | English and explicit Chinese missing | 工卡 %1$s · %2$s | Badge %1$s · %2$s | Same display condition as the issuer baseline above |
| `issuer_issued` | English and explicit Chinese missing | 已登记 | Registered | Same display condition as the issuer baseline above |
| `issuer_lost` | English and explicit Chinese missing | 已挂失 | Reported lost | Same display condition as the issuer baseline above |
| `issuer_revoked` | English and explicit Chinese missing | 已撤销 | Revoked | Same display condition as the issuer baseline above |
| `issuer_other_status` | English and explicit Chinese missing | 其他状态 | Other status | Same display condition as the issuer baseline above |
| `issuer_no_badge` | English and explicit Chinese missing | 此工卡还没有发布的卡面。 | This badge has no published artwork. | Same display condition as the issuer baseline above |
| `issuer_current` | English and explicit Chinese missing | 当前卡面 · %1$s | Current artwork · %1$s | Same display condition as the issuer baseline above |
| `issuer_history` | English and explicit Chinese missing | 历史卡面 · %1$s | Previous artwork · %1$s | Same display condition as the issuer baseline above |
| `issuer_more_history` | English and explicit Chinese missing | 更多历史卡面 | More artwork versions | Same display condition as the issuer baseline above |
| `issuer_snapshot` | English and explicit Chinese missing | 卡面发布时的资料 | Employee details at publication | Same display condition as the issuer baseline above |
| `issuer_published` | English and explicit Chinese missing | 发布时间：%1$s\n发布操作员：%2$s | Published: %1$s\nPublished by: %2$s | Same display condition as the issuer baseline above |
| `issuer_archived` | English and explicit Chinese missing | 卡面存档时间：%1$s | Artwork archive time: %1$s | Same display condition as the issuer baseline above |
| `issuer_front` | English and explicit Chinese missing | 正面 | Front | Same display condition as the issuer baseline above |
| `issuer_back` | English and explicit Chinese missing | 背面 | Back | Same display condition as the issuer baseline above |
| `issuer_address_error` | English and explicit Chinese missing | 请输入 HTTPS 服务地址，不含路径、查询参数或登录信息。 | Enter an HTTPS service address without a path, query parameters, or login details. | Same display condition as the issuer baseline above |
| `issuer_token_required` | English and explicit Chinese missing | 请输入此服务的操作员访问令牌。 | Enter an operator access token for this service. | Same display condition as the issuer baseline above |
| `issuer_unauthorized` | English and explicit Chinese missing | 服务拒绝了访问令牌。请输入有效令牌后重新连接。 | The service rejected the access token. Enter a valid token and reconnect. | Same display condition as the issuer baseline above |
| `issuer_missing` | English and explicit Chinese missing | 服务中已没有所选记录。请重新连接并选择记录。 | The selected record is no longer on the service. Reconnect and select a record. | Same display condition as the issuer baseline above |
| `issuer_redirect` | English and explicit Chinese missing | 服务返回了跳转地址。请核对服务地址后重新连接。 | The service returned a redirect. Check the service address and reconnect. | Same display condition as the issuer baseline above |
| `issuer_invalid_response` | English and explicit Chinese missing | 服务返回的记录或图片格式有误。请联系服务管理员。 | The service returned an invalid record or image. Contact the service administrator. | Same display condition as the issuer baseline above |
| `issuer_failed` | 读取服务失败。请检查网络后重新连接。; English missing | 读取服务失败。请检查网络后重试。 | Could not read from the service. Check the network and try again. | Same display condition as the issuer baseline above |
| `issuer_secret_read_failed` | English and explicit Chinese missing | 此设备保存的访问令牌读取失败。请重新输入令牌。 | Could not read the access token saved on this device. Enter the token again. | Same display condition as the issuer baseline above |
| `issuer_secret_save_failed` | English and explicit Chinese missing | 访问令牌保存失败。请重试连接。 | Could not save the access token. Try connecting again. | Same display condition as the issuer baseline above |
| `issuer_retry_cards` | No section-specific retry control | 重新读取工卡 | Retry badge records | Matching query failure; retry preserves destination and selection |
| `issuer_retry_revisions` | No section-specific retry control | 重新读取卡面版本 | Retry artwork versions | Matching query failure; retry preserves destination and selection |

Native validation includes English and Chinese compact phone, large-text landscape and dark-mode layouts. The test focuses the actual token field, finds the native editor, and obtains its real `EditorInfo` from `onCreateInputConnection`: text/password variation with no autocorrect flag. HTTP/controller and native panel regressions exercise failed card/history reads followed by successful unrelated people pagination, retained errors, no false empty messages, and successful explicit retry. AndroidKeyStore source is unchanged; the root-reported Sony instrumentation pass remains applicable.


## PR 54 second review — credential ownership and paged empty states

Candidate: append-only second review fix after `1e61bd3`. Existing reviewed strings and all locale resources are unchanged. The authorization-error wording continues to describe the rejected token without promising that another saved credential was deleted. A 401 removes a stored credential only when it equals the rejected request token; a valid previously saved token or newer replacement remains available for the explicit blank-token connection action.

The no-employee, no-card and no-artwork messages now require a completed successful query, an empty accumulated collection and no next cursor. The no-artwork message also requires no current revision. An empty intermediate page retains its “more” action and displays no terminal-empty claim. Native regression coverage uses empty first pages for all three collections, then verifies genuine exhausted empty results still display their existing message. No new copy, locale or input-authority change is introduced in this round.

## Approved page artwork integration (2026-10-26)

Client/candidate: Android manager, `feat/approved-page-icons-20261026`, based on `8089aba`.
This change reuses the complete manager copy baseline above and its later native-home reviews. No visible string, localization or action label changes. The generic artwork retains `artwork_generic` in default/loading/failed states; loaded issuer artwork retains its existing source label. The old NFC waves are replaced with the approved neutral C card illustration. Read, card, passkey, certificate, settings and authentication icons are decorative beside their existing labels. Reader entrance/USB/unknown use R1; only the explicit Android NFC reader option uses R2. USB identity and product names never choose contactless artwork. The application launcher icon, navigation and hardware workflows retain their existing behavior. Activation is limited to installation of this candidate; physical-device acceptance is separate.

## Generic IC-card artwork correction (2026-10-26)

Client/candidate: Android manager, `feat/correct-generic-card-chip-20261026`, based on `589dc38`. Reuses the complete copy baseline and existing artwork labels. Generic default/loading/failed and focused-card illustrations now use the approved neutral card face and curved segmented IC module, preserving the 85.60 × 53.98mm face and 13 × 11.8mm module proportions. This supersedes the earlier blue C fallback artwork only. Loaded issuer artwork, visible strings, accessibility descriptions, action conditions, launcher/reader icons and navigation remain unchanged. Activation requires installing this candidate.

## USB detach and explicit reader selection (2026-10-26)

Client/candidate: Android manager, `fix/usb-detach-read-state-20261026`, based on `eb0c2ed`. Reuses the complete manager copy baseline. Trigger: USB mode has no selected reader after detach, including discovery after replug before explicit selection. NFC is not selected automatically. Existing selected-reader, permission, pending-operation and unknown-effect conditions remain in force.

| Screen/key | Before (Chinese / English) | After (Chinese / English) | Reason and activation |
|---|---|---|---|
| Home status / `native_reader_missing` | 尚未读取 / Not read | 未选择读卡器 / No reader selected | Show the actual missing selection; candidate installation |
| Home reader control / `native_choose_reader` | USB / USB | 选择读卡器 / Choose a reader | Opens the existing reader selector; candidate installation |
| Home guidance / `native_choose_reader_hint` | 选择已连接的 USB 读卡器 / Select a connected USB reader | 选择 NFC 或已连接的 USB 读卡器 / Choose NFC or a connected USB reader | Name both existing explicit choices; candidate installation |
| Task reader context / `native_reader_missing` | 读卡器 / 读卡器 | 未选择读卡器 / No reader selected | Use the same explicit selection state in task context; candidate installation |
| Read card control | 读取卡片 / Read card (enabled) | 读取卡片 / Read card (disabled while no reader is selected) | Preserve the action label and prevent an ineligible submission; candidate installation |

Coverage: all three added keys in default Chinese, maintained Chinese and English resources, their no-selection conditions and both native home layouts. Replug discovery leaves the selector available and Read disabled until an explicit USB or NFC choice. PIN preparation and navigation remain available. A raced no-selection submission closes its input without replacing an existing unknown-effect result or starting NFC/card work. Native synthetic render evidence and physical installed-device acceptance are recorded separately in the package handoff; root owns phone capture.

PR #57 localization-gate correction on `fix/usb-detach-read-state-20261026`, parent `79c28b7`: the explicit expected localization scope now includes the three reviewed no-reader keys above, increasing the exact catalog count from 167 to 170. Exact Chinese values, English/Chinese key equality, placeholders, qualified Chinese ownership, locale-list selection and fallback checks remain required. This correction changes only tests and this record; product wording, resources, display conditions and activation are unchanged. The existing 42-case behavior/native UI evidence is reused; the two failed localization suites are checked separately for the corrected candidate.
