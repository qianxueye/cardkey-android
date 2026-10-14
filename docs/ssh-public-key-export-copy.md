# SSH public-key export copy record

Owning record: CardKey Hub Issue 35. Activation: this Android public-key export package only.

Reviewed with plain-language-iso-24495 before application. No existing wording changes.

| Screen/key | Before | After | Reason |
| --- | --- | --- | --- |
| PIV operation / PIV_SSH_EXPORT | absent | 导出 SSH 公钥 | Name the exported object and format. |
| PIV result / ssh_publicKeyScope | absent | 已读取 SSH 公钥。导出后可交给 SSH 服务管理员；私钥仍在卡内。本操作不验证 SSH 登录。 | Separate fresh public-key reading, file export and SSH login acceptance. |
| Export receipt / ssh_publicKeyExported | absent | 已导出 SSH 公钥。 | Report the actual completed file write without claiming private-key authentication. |

The existing cancellation and uncertain-write receipts remain applicable. Public-key export never prompts for PIN, signs, generates a key or writes to the card. It reads current slot metadata and does not require a certificate.

Native tests run by default. The explicit external OpenSSH gate must additionally be run for this package with `CARDKEY_SSH_KEYGEN` set to a usable `ssh-keygen` executable; the test imports each exported file with `ssh-keygen -e -m PKCS8` and compares the reconstructed SPKI. No machine-specific install path is assumed.
