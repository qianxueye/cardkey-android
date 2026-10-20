# UI copy review

Owning issue: qianxueye/cardkey-android#31. Activation: the next build of this candidate; no installation, provider activation or physical-card acceptance is implied.

Applied plain-language-iso-24495 to all manager/provider copy producers, including existing forms, technical results, errors, destructive confirmations, accessibility and file prompts. Reviewed source includes MainActivity, ManagerScreen, PlexoWorkspace, PlexoComponents, ManagerOperation, ManagerViewModel, ConfirmationPolicy, CardRecognition, WorkspaceInput, EnrollmentPanel, TlsQualificationPanel, ProviderActivity, ProviderRecognition, ProviderOperationRunner, all values resources, manifest labels and credential-provider configuration. External card/account/reader/file values are displayed as supplied data, not authored claims. Native platform control accessibility labels are provided by Compose.

Exact changed hardcoded wording: ProviderActivity PIN → FIDO PIN; its shared warning 当前 PIN 或 PUK 输入错误会减少重试次数。确认继续？ → FIDO PIN 输入错误会减少重试次数。确认继续？ (scope the warning to this provider). TlsQualificationPanel PIN → 当前 PIV PIN (identify the signing PIN). Manager reader discovery 读取 → 刷新读卡器 (discovery is not a card read); imported size $it bytes → $it 字节. Navigation FIDO2 / PIV / 读卡器 / 证书申请 → 设备 / 通行密钥 / 证书 / 设置; certificate enrollment remains reachable as 申请 PIV 证书 under Settings. The provider settings entry is 凭据提供者设置.

Keep unknown, applied, failed-readback and cancellation states distinct. Keep PIV last-retry key erasure, credential deletion, resets, management-key replacement, shared Large Blob loss and protected export warnings. Keep certificate trust, revocation, CMS/time and external-account acceptance limits. No success wording is inferred from the design fixture. Provider caller/RP/account verification and attestation privacy disclosures remain explicit. PIN/PUK fields remain native secure fields.

The table records all resource strings and Chinese authored literals in UI/provider/result producers. Unchanged technical English labels (PIN, PUK, HEX, RP, AAGUID, FIDO2, U2F, PIV, hmac-secret, largeBlobKey, credBlob, algorithms and file formats) retain their protocol spelling. Card artwork accessibility accepts a nonblank supplied label up to 200 characters; the generic fallback is explicitly a representation. New artwork state labels refer only to image loading.

| Screen / key or producer | Before | After | Review reason |
| --- | --- | --- | --- |
| app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt / literal | "确认" | "确认" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt / literal | "取消" | "取消" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt / literal | "PIN" | "当前 PIV PIN" | Clarify task or protocol context; no capability change. |
| app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt / literal | "6–8 位数字" | "6–8 位数字" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / literal | "确认" | "确认" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / literal | "取消" | "取消" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / literal | "槽位" | "槽位" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / literal | "算法" | "算法" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / literal | "6–8 位数字" | "6–8 位数字" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / literal | "管理密钥" | "管理密钥" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "凭据 ID · ${it.idHex}" | "凭据 ID · ${it.idHex}" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "确认" | "确认" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "取消" | "取消" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "导出" | "导出" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "凭据 ID" | "凭据 ID" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "{当前验证项}" | "{当前验证项}" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "{另一项}" | "{另一项}" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "读卡器" | "读卡器" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "名称" | "名称" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "公钥" | "公钥" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "用户 ID" | "用户 ID" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "证明" | "证明" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "计数" | "计数" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "支持的协议" | "支持的协议" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "支持的扩展" | "支持的扩展" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "PIN · 协议" | "PIN · 协议" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "算法" | "算法" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "版本" | "版本" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "序列号" | "序列号" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "用户验证方法" | "用户验证方法" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "读取" | "刷新读卡器" | Clarify task or protocol context; no capability change. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "槽位" | "槽位" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "6–8 位数字" | "6–8 位数字" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "新 PUK" | "新 PUK" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / new PIN field | "新 PIN" | "新 FIDO PIN" / "新 PIV PIN" | Identify the operation protocol. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "管理密钥 · 算法" | "管理密钥 · 算法" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "管理密钥 · HEX" | "管理密钥 · HEX" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "导入" | "导入" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "$it bytes" | "$it 字节" | Clarify task or protocol context; no capability change. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "新管理密钥 · 算法" | "新管理密钥 · 算法" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "新管理密钥 · HEX" | "新管理密钥 · HEX" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "PIN 验证策略" | "PIN 验证策略" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "PIN · 最短长度" | "PIN · 最短长度" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "凭据 ID · HEX" | "凭据 ID · HEX" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "始终验证用户" | "始终验证用户" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "修改 PIN" | "修改 PIN" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "驻留凭据" | "驻留凭据" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "厂商预设（1）" | "厂商预设（1）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "平台管理（2）" | "平台管理（2）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "不请求" | "不请求" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "扩展" | "扩展" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "导入证书" | "导入证书" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / literal | "操作" | "操作" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "FIDO2 · 读取" | "FIDO2 · 读取" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "FIDO2 · 重置" | "FIDO2 · 重置" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN · 重试次数" | "PIN · 重试次数" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN · 设置" | "PIN · 设置" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN · 修改" | "PIN · 修改" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "凭据 · 容量" | "凭据 · 容量" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "RP · 读取" | "RP · 读取" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "凭据 · 读取" | "凭据 · 读取" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "凭据 · 删除" | "凭据 · 删除" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "凭据 · 修改" | "凭据 · 修改" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "始终验证用户" | "始终验证用户" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN · 最短长度" | "PIN · 最短长度" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "企业证明 · 启用" | "企业证明 · 启用" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "注册 · 验证" | "注册 · 验证" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "签名 · 验证" | "签名 · 验证" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "验证签名" | "验证签名" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "U2F · 读取" | "U2F · 读取" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "U2F · 注册" | "U2F · 注册" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "U2F · 验证" | "U2F · 验证" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "扩展数据 · 读取" | "扩展数据 · 读取" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "扩展数据 · 设置" | "扩展数据 · 设置" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIV · 读取" | "PIV · 读取" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "证书 · 读取" | "证书 · 读取" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "导出 SSH 公钥" | "导出 SSH 公钥" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PUK · 修改" | "PUK · 修改" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN · 解锁" | "PIN · 解锁" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "管理密钥 · 修改" | "管理密钥 · 修改" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "生成密钥" | "生成密钥" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "导出 CSR" | "导出 CSR" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "导入证书" | "导入证书" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "文件签名" | "文件签名" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "CMS 文件签名" | "CMS 文件签名" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "ECDH · 验证" | "ECDH · 验证" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "RSA · 验证" | "RSA · 验证" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "生成 CSR" | "生成 CSR" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIV · 重置" | "PIV · 重置" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN 剩余重试次数" | "PIN 剩余重试次数" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "${it.pin} 次" | "${it.pin} 次" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "UV 剩余重试次数" | "UV 剩余重试次数" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "$count 次" | "$count 次" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。" | "请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "已存储凭据" | "已存储凭据" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "${it.existing} 个" | "${it.existing} 个" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "剩余凭据容量（估算）" | "剩余凭据容量（估算）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "${it.remaining} 个" | "${it.remaining} 个" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "凭据 ID" | "凭据 ID" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "用户 ID" | "用户 ID" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "名称" | "名称" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "显示名称" | "显示名称" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "支持的协议" | "支持的协议" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "版本" | "版本" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "序列号" | "序列号" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "剩余 ${it.pin.remaining} 次（共 ${it.pin.total} 次）" | "剩余 ${it.pin.remaining} 次（共 ${it.pin.total} 次）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PUK 剩余重试次数" | "PUK 剩余重试次数" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "剩余 ${it.puk.remaining} 次（共 ${it.puk.total} 次）" | "剩余 ${it.puk.remaining} 次（共 ${it.puk.total} 次）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "管理密钥" | "管理密钥" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "槽位" | "槽位" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "证书" | "证书" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "未存储证书" | "未存储证书" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "有效期起止顺序异常" | "有效期起止顺序异常" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "尚未生效" | "尚未生效" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "已过期" | "已过期" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "有效期内" | "有效期内" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "日期状态" | "日期状态" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "签发者" | "签发者" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "证书序列号（十六进制）" | "证书序列号（十六进制）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "生效时间（UTC）" | "生效时间（UTC）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "到期时间（UTC）" | "到期时间（UTC）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "检查时间（设备时钟，UTC）" | "检查时间（设备时钟，UTC）" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "算法" | "算法" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN 验证策略" | "PIN 验证策略" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "无需 PIN" | "无需 PIN" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "会话内验证一次" | "会话内验证一次" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "每次操作验证" | "每次操作验证" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "触摸确认策略" | "触摸确认策略" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "无需触摸" | "无需触摸" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "每次操作触摸" | "每次操作触摸" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "允许缓存触摸确认" | "允许缓存触摸确认" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "公钥" | "公钥" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "支持的扩展" | "支持的扩展" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "无" | "无" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "PIN · 协议" | "PIN · 协议" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "需要修改 PIN" | "需要修改 PIN" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "是" | "是" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "否" | "否" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "已设置" | "已设置" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "未设置" | "未设置" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "始终验证" | "始终验证" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "开启" | "开启" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "关闭" | "关闭" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "企业证明" | "企业证明" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "最短 PIN 长度" | "最短 PIN 长度" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "${it.number()} 个字符" | "${it.number()} 个字符" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "证明" | "证明" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "计数" | "计数" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / literal | "用户验证方法" | "用户验证方法" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_title | 系统凭据请求 | 系统凭据请求 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_create_title | 确认创建凭据 | 确认创建凭据 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_get_title | 确认本次登录 | 确认本次登录 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_verified | 此页面来自系统凭据请求。调用应用与 RP 已通过核验。 | 此页面来自系统凭据请求。调用应用与 RP 已通过核验。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_rp | 网站 · RP | 网站 · RP | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_caller | 调用应用 | 调用应用 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_account | 账户 | 账户 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_verifying | 正在核验请求 | 正在核验请求 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_rejected | 请求未通过核验 | 请求未通过核验 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_result | 本次请求结果 | 本次请求结果 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_accounts | 选择账户 | 选择账户 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_reader | 读卡器 | 读卡器 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_permission | 等待 USB 访问授权 | 等待 USB 访问授权 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_permission_prompt | 在系统提示中决定是否允许 %1$s 访问此 USB 读卡器。 | 在系统提示中决定是否允许 %1$s 访问此 USB 读卡器。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_confirmation | 确认操作 | 确认操作 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_get_consequence | 将使用这张卡登录所列网站。 | 将使用这张卡登录所列网站。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_create_consequence | 将在这张卡上创建 FIDO 凭据。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。 | 将在这张卡上创建 FIDO 凭据。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_confirm | 确认 | 确认 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_cancel | 取消 | 取消 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_close | 关闭 | 关闭 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_recognition | 先识别卡片 | 先识别卡片 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_recognition_explanation | 先读取卡片的 FIDO2 信息。识别后再确认创建或登录；需要 PIN 时才显示输入框。 | 先读取卡片的 FIDO2 信息。识别后再确认创建或登录；需要 PIN 时才显示输入框。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_recognize | 识别卡片 | 识别卡片 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_recognizing | 正在识别卡片 | 正在识别卡片 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_recognition_nfc | 请将卡片贴近手机，读取 FIDO2 信息。 | 请将卡片贴近手机，读取 FIDO2 信息。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_recognized | 已读取 FIDO2 信息。请确认本次操作。 | 已读取 FIDO2 信息。请确认本次操作。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_keep_nfc | 请保持卡片贴近手机。连接中断后，本次请求会结束。 | 请保持卡片贴近手机。连接中断后，本次请求会结束。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_provider.xml / plexo_provider_executing | 正在处理凭据请求 | 正在处理凭据请求 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_currentRequest | 本次申请 | 本次申请 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_unknownRequest | CA 签发结果待核实 | CA 签发结果待核实 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_identity | 申请身份 | 申请身份 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_purpose | 用途 | 用途 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_documentIdentity | 文档签名证书 | 文档签名证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_tlsIdentity | TLS 客户端证书 | TLS 客户端证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_slotAlgorithm | 槽位与算法 | 槽位与算法 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_service | 签发服务 | 签发服务 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_root | 签发根证书 | 签发根证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_prepare | 申请准备 | 申请准备 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_csr | 卡内密钥与 CSR | 卡内密钥与 CSR | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_issue | 向 CA 申请 | 向 CA 申请 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_importExport | 证书导入与导出 | 证书导入与导出 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_rootReady | 已选择 CA 根证书 | 已选择 CA 根证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_importReview | 检查导入确认 | 检查导入确认 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_ca_exportChain | 导出已验证的证书链 | 导出已验证的证书链 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_tls_result | 本次连接结果 | 本次连接结果 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_tls_certificate | 连接所用证书 | 连接所用证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/plexo_ca.xml / plexo_tls_service | 服务与认证 | 服务与认证 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / app_name | CardKey | CardKey | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / operation_completed | 操作已完成。 | 操作已完成。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / operation_notDispatched | 指令未发送到卡片。 | 指令未发送到卡片。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / operation_unknown | 指令已发送，但未收到结果。更改可能已生效；请先读取卡片状态，再决定下一步。 | 指令已发送，但未收到结果。更改可能已生效；请先读取卡片状态，再决定下一步。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / operation_appliedReadbackFailed | 卡片已接受更改，但回读未完成。请读取卡片状态，勿重复提交更改。 | 卡片已接受更改，但回读未完成。请读取卡片状态，勿重复提交更改。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_contactRequired | 此操作需要接触式读卡器。NFC 目前仅支持读取 PIV 公开信息。 | 此操作需要接触式读卡器。NFC 目前仅支持读取 PIV 公开信息。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_physicalActionRequired | 请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。 | 请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_pinInvalid | PIN 或 PUK 验证失败，请查看剩余重试次数。 | PIN 或 PUK 验证失败，请查看剩余重试次数。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_pinBlocked | PIN 或 PUK 已锁定。 | PIN 或 PUK 已锁定。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_unsupported | 当前卡片或读卡器不支持此操作。 | 当前卡片或读卡器不支持此操作。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_authorization | 卡片未授权此操作。 | 卡片未授权此操作。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_malformed | 卡片响应无效。 | 卡片响应无效。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_keyMismatch | 证书公钥与所选槽位不匹配，未导入证书。 | 证书公钥与所选槽位不匹配，未导入证书。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_invalidInput | 输入格式有误，请检查所填内容。 | 输入格式有误，请检查所填内容。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_transport | 卡片连接失败。 | 卡片连接失败。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_cancelled | 操作已取消。已发送的指令可能已生效，请查看卡片状态。 | 操作已取消。已发送的指令可能已生效，请查看卡片状态。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / reader_nfc | 选择操作后，将卡片贴近手机。 | 选择操作后，将卡片贴近手机。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / reader_usbPermission | 请在系统提示中允许 %1$s 访问此 USB 读卡器。 | 请在系统提示中允许 %1$s 访问此 USB 读卡器。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / reader_empty | 未连接受支持的 USB 读卡器。 | 未连接受支持的 USB 读卡器。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_authenticate | 当前 PIN 或 PUK 输入错误会减少重试次数。确认继续？ | 当前 PIN 或 PUK 输入错误会减少重试次数。确认继续？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_pinChange | 当前 PIN 或 PUK 输入错误会减少重试次数。确认修改？ | 当前 PIN 或 PUK 输入错误会减少重试次数。确认修改？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_lastRetryErasesKeys | {当前验证项} 仅剩一次重试，{另一项} 已锁定。此次验证失败将清除 PIV 私钥。确认继续？ | {当前验证项} 仅剩一次重试，{另一项} 已锁定。此次验证失败将清除 PIV 私钥。确认继续？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_adminChange | 更换后，后续管理操作需要新管理密钥。请先妥善保存新密钥。确认更换？ | 更换后，后续管理操作需要新管理密钥。请先妥善保存新密钥。确认更换？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_credentialDelete | 删除所选凭据后，无法再用它登录已绑定的账户。此操作不可撤销。确认删除？ | 删除所选凭据后，无法再用它登录已绑定的账户。此操作不可撤销。确认删除？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_credentialUpdate | 更新所选凭据的用户信息，用户 ID 保留。确认更新？ | 更新所选凭据的用户信息，用户 ID 保留。确认更新？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_alwaysUvEnable | 开启后，创建凭据和登录验证始终要求用户验证，U2F 将不可用。确认开启？ | 开启后，创建凭据和登录验证始终要求用户验证，U2F 将不可用。确认开启？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_alwaysUvDisable | 关闭后，创建凭据和登录验证按卡片和凭据各自的策略要求用户验证。确认关闭？ | 关闭后，创建凭据和登录验证按卡片和凭据各自的策略要求用户验证。确认关闭？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_minPin | 最短 PIN 长度不能降低。提高长度后，卡片可能要求修改 PIN；选择强制修改时，须先修改 PIN 才能再次进行 PIN 验证。同时替换最短 PIN 长度的披露名单。确认应用？ | 最短 PIN 长度不能降低。提高长度后，卡片可能要求修改 PIN；选择强制修改时，须先修改 PIN 才能再次进行 PIN 验证。同时替换最短 PIN 长度的披露名单。确认应用？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_enterprise | 按卡内既有策略开启企业证明，允许的服务可能据此识别这张卡。确认开启？ | 按卡内既有策略开启企业证明，允许的服务可能据此识别这张卡。确认开启？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_keyGenerate | 生成新密钥将覆盖所选槽位的现有私钥，原证书可能不再匹配。此操作不可撤销。确认生成？ | 生成新密钥将覆盖所选槽位的现有私钥，原证书可能不再匹配。此操作不可撤销。确认生成？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_certificateImport | 公钥匹配验证通过后，将替换所选槽位的证书。确认导入？ | 公钥匹配验证通过后，将替换所选槽位的证书。确认导入？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_registration | 将为 cardkey.example 创建测试凭据；驻留凭据会占用卡内存储。确认创建？ | 将为 cardkey.example 创建测试凭据；驻留凭据会占用卡内存储。确认创建？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_pivReset | 仅在 PIN 和 PUK 均已锁定时重置 PIV。PIV 私钥此前已清除；重置将清除 PIV 数据，并恢复默认 PIN、PUK 和管理密钥。FIDO 凭据保留。确认重置？ | 仅在 PIN 和 PUK 均已锁定时重置 PIV。PIV 私钥此前已清除；重置将清除 PIV 数据，并恢复默认 PIN、PUK 和管理密钥。FIDO 凭据保留。确认重置？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / restriction_fidoReset | 此卡不提供 CTAP 重置。重装 FIDO applet 需在桌面初始化流程中执行，现有 FIDO 凭据将失效。 | 此卡不提供 CTAP 重置。重装 FIDO applet 需在桌面初始化流程中执行，现有 FIDO 凭据将失效。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / status_verification | 此结果仅验证卡片返回的密码运算、CSR 或签名，不代表证书受信任或外部账户接受。 | 此结果仅验证卡片返回的密码运算、CSR 或签名，不代表证书受信任或外部账户接受。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / provider_settings | 在 Android 设置中选择并启用 %1$s，然后返回。 | 在 Android 设置中选择并启用 %1$s，然后返回。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / provider_attestation_none | 本次注册不发送卡片证明证书，AAGUID 将置零。 | 本次注册不发送卡片证明证书，AAGUID 将置零。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / provider_attestation_direct | 该服务请求卡片证明，响应可能包含可识别这张卡的证书。 | 该服务请求卡片证明，响应可能包含可识别这张卡的证书。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / provider_choose_account | 选择用于登录 %1$s 的账户。 | 选择用于登录 %1$s 的账户。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / provider_request_rejected | 无法验证请求应用或请求内容，未向卡片发送指令。 | 无法验证请求应用或请求内容，未向卡片发送指令。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / minPin_rps | 披露最短 PIN 长度的 RP ID（每行一个，最多 2 个） | 披露最短 PIN 长度的 RP ID（每行一个，最多 2 个） | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / minPin_rpsHelp | 仅这些 RP 可读取最短 PIN 长度。留空将清空名单；名单保存在卡内，客户端无法回读。 | 仅这些 RP 可读取最短 PIN 长度。留空将清空名单；名单保存在卡内，客户端无法回读。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enterprise_mode | 企业证明模式 | 企业证明模式 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / user_updateHelp | 此卡仅支持修改用户名，不支持修改显示名称。 | 此卡仅支持修改用户名，不支持修改显示名称。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_secretRead | 将读取凭据的秘密输出。它不会显示在普通结果中，也不会自动导出。确认读取？ | 将读取凭据的秘密输出。它不会显示在普通结果中，也不会自动导出。确认读取？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / secret_view | 查看秘密输出 | 查看秘密输出 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_secretShow | 秘密输出可用于派生密钥，请勿分享或粘贴到日志。确认显示？ | 秘密输出可用于派生密钥，请勿分享或粘贴到日志。确认显示？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / blob_help | Large Blob 按所选凭据加密。读取或写入前，请先验证该凭据并读取 largeBlobKey；密钥仅供下一次操作使用。 | Large Blob 按所选凭据加密。读取或写入前，请先验证该凭据并读取 largeBlobKey；密钥仅供下一次操作使用。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_blobExport | 将导出此凭据解密后的 Large Blob 内容。请保存在受保护的位置。确认导出？ | 将导出此凭据解密后的 Large Blob 内容。请保存在受保护的位置。确认导出？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_blobWrite | 将写入此凭据的 Large Blob。若共享数据已损坏，写入会重建存储，其他凭据的 Large Blob 数据可能丢失。确认写入？ | 将写入此凭据的 Large Blob。若共享数据已损坏，写入会重建存储，其他凭据的 Large Blob 数据可能丢失。确认写入？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_nfcUnavailable | 此设备不支持 NFC。请连接受支持的 USB 读卡器。 | 此设备不支持 NFC。请连接受支持的 USB 读卡器。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_nfcDisabled | NFC 未开启。请开启 NFC，或连接受支持的 USB 读卡器。 | NFC 未开启。请开启 NFC，或连接受支持的 USB 读卡器。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_capacityExceeded | 卡片存储空间不足，或已达到此操作的数量或大小限制。 | 卡片存储空间不足，或已达到此操作的数量或大小限制。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_recoveryRequired | 卡片状态与预期不符。请先读取卡片状态，勿重复提交更改。 | 卡片状态与预期不符。请先读取卡片状态，勿重复提交更改。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_cardRejected | 卡片拒绝了此操作。请检查卡片状态及操作前提。 | 卡片拒绝了此操作。请检查卡片状态及操作前提。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / status_hidUserPresenceRequired | 请触摸安全密钥或按下按键。 | 请触摸安全密钥或按下按键。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_hidPhysicalActionRequired | 请重新发起操作，再触摸安全密钥或按下按键。 | 请重新发起操作，再触摸安全密钥或按下按键。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / error_hidPowerCycleRequired | PIN 验证暂时锁定。请断开安全密钥的所有电源，再重新连接。 | PIN 验证暂时锁定。请断开安全密钥的所有电源，再重新连接。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / confirm_fidoReset | 重置会使此设备上的全部 FIDO2／U2F 凭据失效，清除 FIDO PIN、相关数据和设置，无法撤销。之后需重新绑定账户。其他功能和证明配置是否保留，取决于设备。请按设备要求完成物理确认。确认重置？ | 重置会使此设备上的全部 FIDO2／U2F 凭据失效，清除 FIDO PIN、相关数据和设置，无法撤销。之后需重新绑定账户。其他功能和证明配置是否保留，取决于设备。请按设备要求完成物理确认。确认重置？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / operation_fidoResetReadback | 设备已接受 FIDO 重置，当前状态已读取。 | 设备已接受 FIDO 重置，当前状态已读取。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_title | PIV／PKI | PIV／PKI | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_address | CA 地址（HTTPS） | CA 地址（HTTPS） | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_chooseRoot | 选择 CA 根证书 | 选择 CA 根证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_cn | 通用名称（CN） | 通用名称（CN） | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_purpose | 客户端认证（TLS） | 客户端认证（TLS） | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_document | 文档签名 | 文档签名 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_documentScope | 使用所选槽位的现有密钥申请文档签名证书。不会生成或替换密钥。 | 使用所选槽位的现有密钥申请文档签名证书。不会生成或替换密钥。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_document9a | 导入后将替换 9A 的现有证书，该文档签名证书不能用于 TLS 客户端认证。 | 导入后将替换 9A 的现有证书，该文档签名证书不能用于 TLS 客户端认证。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_token | 一次性授权令牌 | 一次性授权令牌 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_createCsr | 生成 CSR | 生成 CSR | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_exportCsr | 导出 CSR | 导出 CSR | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_issue | 申请证书 | 申请证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_steps | 先生成并导出 CSR，再获取 CA 管理员提供的一次性授权令牌。 | 先生成并导出 CSR，再获取 CA 管理员提供的一次性授权令牌。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_scope | 令牌必须绑定此 CSR，并由 CA 管理员按所选用途授权。槽位仅决定本地导入位置。 | 令牌必须绑定此 CSR，并由 CA 管理员按所选用途授权。槽位仅决定本地导入位置。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_csrReady | CSR 已生成，可导出后交给 CA 管理员。 | CSR 已生成，可导出后交给 CA 管理员。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_issuing | 正在向所选 CA 申请证书。 | 正在向所选 CA 申请证书。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_verified | CA 已签发证书，证书链、用途、身份和公钥验证通过。证书尚未写入卡片。 | CA 已签发证书，证书链、用途、身份和公钥验证通过。证书尚未写入卡片。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_unknown | 未确认签发结果。请先向 CA 管理员核实，勿重复提交。 | 未确认签发结果。请先向 CA 管理员核实，勿重复提交。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_rejected | CA 拒绝了申请，未写入卡片。 | CA 拒绝了申请，未写入卡片。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_invalid | 证书未通过验证，未写入卡片。 | 证书未通过验证，未写入卡片。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_confirmImport | 将已验证的证书写入所选 PIV 槽位，替换该槽位的现有证书。私钥不变。确认导入？ | 将已验证的证书写入所选 PIV 槽位，替换该槽位的现有证书。私钥不变。确认导入？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / enrollment_imported | 证书已写入所选槽位，并完成回读。 | 证书已写入所选槽位，并完成回读。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_title | 验证 TLS 连接 | 验证 TLS 连接 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_scope | 在本 App 内验证所选证书到指定服务器的连接。 | 在本 App 内验证所选证书到指定服务器的连接。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_endpoint | 服务器地址 | 服务器地址 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_endpointHelp | 填写 HTTPS 域名地址，不带查询参数或片段。服务器须支持 TLS 1.3，证书包含完整域名，测试接口返回无正文的 HTTP 204。 | 填写 HTTPS 域名地址，不带查询参数或片段。服务器须支持 TLS 1.3，证书包含完整域名，测试接口返回无正文的 HTTP 204。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_serverRoot | 服务器根证书 | 服务器根证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_chooseServerRoot | 选择服务器根证书 | 选择服务器根证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_serverRootReady | 已选择服务器根证书。 | 已选择服务器根证书。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_serverRootHelp | 用所选根证书验证服务器，仅用于本次测试。 | 用所选根证书验证服务器，仅用于本次测试。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_identity | 使用证书 | 使用证书 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_validUntil | 有效期至 | 有效期至 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_start | 验证连接 | 验证连接 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_confirm | 将向 %1$s 发送所选证书，并使用卡内私钥进行一次认证签名。PIN 输入错误会减少剩余重试次数。确认继续？ | 将向 %1$s 发送所选证书，并使用卡内私钥进行一次认证签名。PIN 输入错误会减少剩余重试次数。确认继续？ | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_running | 正在验证连接… | 正在验证连接… | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_success | 已完成 TLS 握手，服务器返回 HTTP 204。请在服务器记录中核对证书与账户。 | 已完成 TLS 握手，服务器返回 HTTP 204。请在服务器记录中核对证书与账户。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_failed | 连接验证未完成。 | 连接验证未完成。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_cancelled | 已取消连接验证。 | 已取消连接验证。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_timeout | 连接验证已超时。 | 连接验证已超时。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_mayHaveSubmitted | 认证可能已提交，请核对服务器记录。 | 认证可能已提交，请核对服务器记录。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / tls_unknown | 未确认认证结果。请先核对服务器记录，勿重复提交。 | 未确认认证结果。请先核对服务器记录，勿重复提交。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_chooseFile | 选择文件 | 选择文件 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_limit | 选择非空文件，最大 1 MiB。 | 选择非空文件，最大 1 MiB。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_selectedFile | %1$s · %2$d 字节 | %1$s · %2$d 字节 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_verified | 签名验证：通过 | 签名验证：通过 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_exportScope | 导出的是所选文件的独立签名；原文件保持不变。 | 导出的是所选文件的独立签名；原文件保持不变。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_exported | 已导出签名。 | 已导出签名。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_exportCancelled | 已取消导出。 | 已取消导出。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_exportUnknown | 未确认导出结果。请检查所选文件。 | 未确认导出结果。请检查所选文件。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_unread | 卡片状态尚未读取 | 卡片状态尚未读取 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_busy | 操作执行中 | 操作执行中 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_lastResult | 最近操作结果见下方；不代表实时卡片状态 | 最近操作结果见下方；不代表实时卡片状态 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_inspection | 结果待核实；请先读取卡片状态 | 结果待核实；请先读取卡片状态 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_common | 常用任务 | 常用任务 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_advanced | 高级与协议诊断 | 高级与协议诊断 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_details | 技术详情 | 技术详情 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_slotContext | 槽位选择用于本次操作；证书状态需单独读取。 | 槽位选择用于本次操作；证书状态需单独读取。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_credentialsUnread | 凭据列表尚未读取；先读取 RP，再读取凭据。 | 凭据列表尚未读取；先读取 RP，再读取凭据。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_enrollmentFlow | 生成 CSR → CA 签发 → 验证证书 → 导入并读回 | 生成 CSR → CA 签发 → 验证证书 → 导入并读回 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_cmsRequirements | 需要有效的文档签名证书，且证书公钥与所选槽位一致。 | 需要有效的文档签名证书，且证书公钥与所选槽位一致。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_cmsVerified | CMS 签名验证：通过 | CMS 签名验证：通过 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_cmsScope | 导出 .p7s 独立签名，包含签名证书；验证时还需原文件。原文件保持不变。 | 导出 .p7s 独立签名，包含签名证书；验证时还需原文件。原文件保持不变。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / signing_cmsTrust | 未验证证书信任链、吊销状态或可信时间。 | 未验证证书信任链、吊销状态或可信时间。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / ssh_publicKeyScope | 已读取 SSH 公钥。导出后可交给 SSH 服务管理员；私钥仍在卡内。本操作不验证 SSH 登录。 | 已读取 SSH 公钥。导出后可交给 SSH 服务管理员；私钥仍在卡内。本操作不验证 SSH 登录。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / ssh_publicKeyExported | 已导出 SSH 公钥。 | 已导出 SSH 公钥。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / certificate_dateScope | 仅检查证书有效期，未验证信任链、吊销状态或与槽位公钥的匹配关系。 | 仅检查证书有效期，未验证信任链、吊销状态或与槽位公钥的匹配关系。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_disconnected | 当前连接 · 未选择 USB 读卡器 | 当前连接 · 未选择 USB 读卡器 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_objects | 对象列表 | 对象列表 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_selectedObject | 当前选择 | 当前选择 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_selectedSlot | 槽位 · %1$s | 槽位 · %1$s | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_selectionOnly | 选择对象或任务不会发送卡片指令。 | 选择对象或任务不会发送卡片指令。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_authenticator | 认证器 | 认证器 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_pivSecurity | PIV 状态与安全设置 | PIV 状态与安全设置 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_slotCatalog | 槽位目录 | 槽位目录 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_slotAuthentication | 身份验证 | 身份验证 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_slotSignature | 数字签名 | 数字签名 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_slotManagement | 密钥管理 | 密钥管理 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_slotCardAuthentication | 卡片验证 | 卡片验证 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_slotRetired | 退役密钥 | 退役密钥 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_credentials | 凭据 | 凭据 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_credentialReadRequired | 选择 RP 后，明确执行“凭据 · 读取”。当前列表不代表卡片没有凭据。 | 选择 RP 后，明确执行“凭据 · 读取”。当前列表不代表卡片没有凭据。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_tasks | 选择任务 | 选择任务 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_receipt | 任务结果 | 任务结果 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_receiptOwner | 以下结果属于本次任务记录的对象。改变当前选择不会改变结果中的对象。 | 以下结果属于本次任务记录的对象。改变当前选择不会改变结果中的对象。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_pivReaderRequired | 当前读卡器使用 CTAP，只能访问 FIDO。PIV 和证书申请需要 NFC 或支持 APDU 的 USB 读卡器。 | 当前读卡器使用 CTAP，只能访问 FIDO。PIV 和证书申请需要 NFC 或支持 APDU 的 USB 读卡器。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_outputFile | 本次导出文件 | 本次导出文件 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_readFidoFirst | 先执行“FIDO2 · 读取”，识别认证器后再输入 PIN 或扩展数据。读取记录不代表卡片持续在场。 | 先执行“FIDO2 · 读取”，识别认证器后再输入 PIN 或扩展数据。读取记录不代表卡片持续在场。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_readPivFirst | 先执行“PIV · 读取”，识别卡片后再输入 PIN、PUK 或管理密钥。读取记录不代表卡片持续在场。 | 先执行“PIV · 读取”，识别卡片后再输入 PIN、PUK 或管理密钥。读取记录不代表卡片持续在场。 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / workspace_readRecognition | 读取卡片信息 | 读取卡片信息 | Retain: relevant label or scope; conditions and uncertainty remain explicit. |
| app/src/main/res/values/strings.xml / navigation_device | Absent (new string) | 设备 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / navigation_passkeys | Absent (new string) | 通行密钥 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / navigation_certificates | Absent (new string) | 证书 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / navigation_settings | Absent (new string) | 设置 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / device_reader | Absent (new string) | 连接方式与读卡器 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / settings_services | Absent (new string) | 系统与证书服务 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / settings_provider | Absent (new string) | 凭据提供者设置 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / settings_enrollment | Absent (new string) | 申请 PIV 证书 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / artwork_generic | Absent (new string) | 通用卡片示意图 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / artwork_loading | Absent (new string) | 正在加载卡片图片… | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / artwork_failed | Absent (new string) | 卡片图片加载失败，仍可使用卡片功能。 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / artwork_retry | Absent (new string) | 重试图片 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / provider_confirm_fido | Absent (new string) | FIDO PIN 输入错误会减少重试次数。确认继续？ | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / workspace_sites | Absent (new string) | 网站与凭据 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / workspace_security | Absent (new string) | 安全设置 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / workspace_fidoPin | Absent (new string) | 更改 FIDO PIN | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / workspace_createOnWebsite | Absent (new string) | 新通行密钥由网站发起创建。这里可查看支持驻留的凭据。 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/res/values/strings.xml / workspace_readSites | Absent (new string) | 读取网站列表 | Clarify task, scope or presentation; preserve command behavior. |
| app/src/main/java/cc/cardkey/MainActivity.kt / literal | "凭据提供者" | "凭据提供者" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/MainActivity.kt / literal | "启用" | "启用" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |
| app/src/main/java/cc/cardkey/MainActivity.kt / literal | "取消" | "取消" | Retain reviewed wording; preserve technical identifiers, limits and consequences. |

## Technical labels retained

| Producer | Before | After | Review reason |
| --- | --- | --- | --- |
| app/src/main/java/cc/cardkey/provider/RequestParser.kt / technical literal | "name" | "name" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/provider/RequestParser.kt / technical literal | "displayName" | "displayName" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/provider/ProviderActivity.kt / technical literal | "${index + 1}" | "${index + 1}" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/provider/ProviderActivity.kt / technical literal | "NFC" | "NFC" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/provider/ProviderActivity.kt / technical literal | "USB" | "USB" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/provider/ProviderActivity.kt / PIN field | "PIN" | "FIDO PIN" | Identify the FIDO PIN used by this request. |
| app/src/main/java/cc/cardkey/ui/TlsQualificationPanel.kt / technical literal | "P-256 · 9A" | "P-256 · 9A" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / technical literal | "%02X" | "%02X" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / technical literal | "P-256 · 9A" | "P-256 · 9A" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / PIN field | "PIN" | "当前 PIV PIN" | Identify the PIV PIN used for certificate operations. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / technical literal | "enrollment-issue" | "enrollment-issue" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/EnrollmentPanel.kt / technical literal | "enrollment-cancel" | "enrollment-cancel" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/CardArtworkHero.kt / technical literal | "CardKey" | "CardKey" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/CardArtworkHero.kt / technical literal | "FIDO2 / PIV" | "FIDO2 / PIV" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "RP · ${it.rp.id}" | "RP · ${it.rp.id}" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "${it.label} · ${it.value}" | "${it.label} · ${it.value}" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "PIV" | "PIV" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "NFC" | "NFC" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "·" | "·" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "%02X" | "%02X" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "hmac-secret: " | "hmac-secret: " | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "largeBlobKey: " | "largeBlobKey: " | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "credBlob: " | "credBlob: " | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "0x%04X" | "0x%04X" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "RP" | "RP" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "signing-file-picker" | "signing-file-picker" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / current PUK field | "PUK" | "当前 PUK" | Identify the current unlocking secret. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / current PIN field | "PIN" | "当前 FIDO PIN" / "当前 PIV PIN" | Select the label from the operation protocol. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "largeBlobKey" | "largeBlobKey" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "uvm" | "uvm" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "credProtect" | "credProtect" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "credBlob · HEX" | "credBlob · HEX" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "hmac-secret · salt · HEX" | "hmac-secret · salt · HEX" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / technical literal | "${it.size} bytes" | "${it.size} bytes" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "RP" | "RP" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "RP SHA-256" | "RP SHA-256" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "bytes" | "bytes" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "credential-blob.bin" | "credential-blob.bin" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "%02X" | "%02X" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "CSR" | "CSR" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "CERTIFICATE REQUEST" | "CERTIFICATE REQUEST" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "piv-${input.slot.toString(16)}.csr" | "piv-${input.slot.toString(16)}.csr" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "clientPin" | "clientPin" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "PIN" | "PIN" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "alwaysUv" | "alwaysUv" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "ep" | "ep" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "credProtect" | "credProtect" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "minPinLength" | "minPinLength" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "credBlob" | "credBlob" | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "; " | "; " | Retain exact protocol, identifier, format or technical label. |
| app/src/main/java/cc/cardkey/ui/ManagerOperation.kt / technical literal | "cardkey.example" | "cardkey.example" | Retain exact protocol, identifier, format or technical label. |

## Familiar task context

PlexoWorkspace protocol subtitle: absent → FIDO2 for Passkeys, PIV for Certificates. These are protocol labels, not certification claims. Credential row secondary text: credential.idHex → credential.rp.id (show website beside account; the credential ID remains in selected-object details). No cloud, biometric, creation-time or last-used state is added. Existing in-repository key icon is retained without a third-party asset dependency.

| app/src/main/java/cc/cardkey/ui/ManagerScreen.kt / new PIN field | "新 PIN" | "新 FIDO PIN" / "新 PIV PIN" | Select the label from the operation protocol; new PUK remains "新 PUK". |

## PR 32 account-disambiguation correction

Screen/key: PlexoObjects / credential row. Before: title = displayName, then name, then full credential ID; secondary text = RP ID only. After: keep the display-name title, show the actual nonblank username when it differs from that title, then RP ID. If the username is missing or the resulting account text collides with another row for the same RP, also show the complete credential ID; a full-ID title is not repeated. Exact added label: absent → `凭据 ID · {full idHex}`. This reuses the existing selected-object vocabulary. Names and identifiers are supplied public credential data; no name or binding is inferred. Text wraps without ellipsis so the distinguishing ID bytes remain available. Activation: the next build of this same candidate; selection/verification/deletion commands and provider behavior are unchanged. Reviewed with plain-language-iso-24495 for identification, scope and task clarity.
