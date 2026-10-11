# Approved provider copy

The Owner approved the seven provider messages on 2026-10-10, followed by the naming-only instruction to use one application display-name resource. These messages are active in `app/src/main/res/values/strings.xml`. The `app_name` resource is the single product display name; its current value is CardKey. Package IDs, RP IDs, AIDs, signer identities and diagnostic credential identities are independent protocol values.

| Resource key | Approved Chinese text |
| --- | --- |
| `provider_settings` | 在 Android 设置中选择并启用 {appName}，然后返回。 |
| `provider_create_confirmation` | 为 {rpId} 在这张卡上创建 FIDO 凭据？请求应用：{callerPackage}。驻留凭据会占用卡内存储，此 App 无法将凭据复制到另一张卡。 |
| `provider_get_confirmation` | 使用这张卡登录 {rpId}？请求应用：{callerPackage}。 |
| `provider_attestation_none` | 本次注册不发送卡片证明证书，AAGUID 将置零。 |
| `provider_attestation_direct` | 该服务请求卡片证明，响应可能包含可识别这张卡的证书。 |
| `provider_choose_account` | 选择用于登录 {rpId} 的账户。 |
| `provider_request_rejected` | 无法验证请求应用或请求内容，未向卡片发送指令。 |

`rpId` and `callerPackage` are replaced only with verified request data. The rejection message applies only to validation failures before card connection. Invalid card responses use the existing approved card/error outcome messages. Returning a credential response does not establish external login acceptance.

The existing approved USB permission text now formats the same `app_name` resource: `请在系统提示中允许 {appName} 访问此 USB 读卡器。` Its meaning is unchanged. Existing PIN, NFC, unknown-outcome and physical-action messages are reused.
