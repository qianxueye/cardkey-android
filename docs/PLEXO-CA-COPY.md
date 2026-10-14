# Plexo certificate and TLS presentation copy

Scope: the existing Android certificate-application destination and its qualified TLS panel. Reviewed with `plain-language-iso-24495` against the approved Android Plexo design. Existing callback, confirmation, expiry, unknown-result and native file semantics remain authoritative. Root records this batch in [CardKey issue 35](https://github.com/qianxueye/cardkey/issues/35), which remains open. This record authorizes no new backend capability or device action; activation occurs when the composed application is merged and installed.

New resources are confined to `app/src/main/res/values/plexo_ca.xml`; the shell owns `strings.xml`.

| Screen / key | Before | After | Reason and activation |
| --- | --- | --- | --- |
| Enrollment / `plexo_ca_currentRequest` | Status mixed into one form | 本次申请 | Captured request facts and actual stage precede editable fields whenever a CSR exists. |
| Enrollment / `plexo_ca_unknownRequest` | Unknown text, CN and CA shown inline | CA 签发结果待核实 | Each retained unknown receipt remains visible with its original purpose, slot/algorithm, CN, CA endpoint and root. Existing administrator/retry warning remains exact. |
| Enrollment / `plexo_ca_identity` | CN beside current form | 申请身份 | Distinguish the captured CSR identity from the editable CN field. |
| Enrollment / `plexo_ca_purpose` | Purpose only in controls | 用途 | Label the purpose carried by the captured CSR. |
| Enrollment / `plexo_ca_documentIdentity` | 文档签名 in selector only | 文档签名证书 | Name the document purpose in captured request facts; selector wording stays unchanged. |
| Enrollment / `plexo_ca_tlsIdentity` | 客户端认证（TLS） in selector only | TLS 客户端证书 | Name the TLS purpose in captured facts without changing its 9A/P-256 boundary. |
| Enrollment / `plexo_ca_slotAlgorithm` | Separate editable choices | 槽位与算法 | Display the captured pair, such as `9C · P384`; never infer key presence from selection. |
| Enrollment / `plexo_ca_service` | CA address appeared among inputs | 签发服务 | The receipt uses its original endpoint; a current CSR uses its still-owned configured address. |
| Enrollment / `plexo_ca_root` | Captured root not displayed | 签发根证书 | Show the subject of the root already captured by the immutable CA receipt; no new trust fetch or validation policy. |
| Enrollment / `plexo_ca_prepare` | One long form | 申请准备 | Group existing purpose, slot/algorithm, CA, CN and native root picker. |
| Enrollment / `plexo_ca_csr` | PIN and CSR control inline | 卡内密钥与 CSR | Group the existing PIN field and explicit CSR confirmation; no key generation. |
| Enrollment / `plexo_ca_issue` | Token and issuance inline | 向 CA 申请 | Separate export/token/one-shot issuance from certificate import. |
| Enrollment / `plexo_ca_importExport` | Management key and export inline | 证书导入与导出 | Group verified-chain import confirmation and secondary export. |
| Enrollment / `plexo_ca_rootReady` | CA 根证书 | 已选择 CA 根证书 | State the existing selection result without implying card import. |
| Enrollment / `plexo_ca_importReview` | 导入证书 | 检查导入确认 | Filled primary control opens the same captured PendingOperation confirmation; it does not write immediately. |
| Enrollment / `plexo_ca_exportChain` | 导出 | 导出已验证的证书链 | Outlined secondary control exports the same captured chain/report; it does not imply saved-file success. |
| TLS / `plexo_tls_result` | Outcome inline above fields | 本次连接结果 | Separate TLS outcome and submission uncertainty from enrollment status. Existing outcome/recovery strings remain exact. |
| TLS / `plexo_tls_certificate` | Identity facts inline | 连接所用证书 | Group existing imported identity, fixed slot/algorithm and expiry. |
| TLS / `plexo_tls_service` | Endpoint, server-root picker and PIN inline | 服务与认证 | Group actual service, native root selection and explicit authentication confirmation. |

Retained text includes all existing status/unknown/rejected/invalid/imported messages, purpose choices, document scope and 9A replacement consequences, token-binding scope, PIN limits, management algorithms, TLS limits/root guidance/outcomes, and native confirmation/cancel wording. Material uncertainty remains outside technical disclosure. Busy controls, secret clearing, revision/CSR/bundle ownership and existing cancellation eligibility are unchanged. No sample identity, fixture value, design marker or invented completion percentage enters the application.
