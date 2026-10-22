# Protected PIV interface copy

All rows were reviewed before application using the plain-language skill. New copy has no prior interface wording. The review preserves the issuer container trust boundary, memory lifetime, recognition/execution separation, PIN retry effects, uncertain outcomes and contact-only operations. These strings activate only in the protected PIV panel in this application candidate. They do not establish deployment or physical card acceptance.

Dynamic values are public issuer identity/fingerprint, selected file metadata, profile GUID, authenticated retry count and pending action/slot. Existing manager receipt and failure rendering preserves the original operation outcome.

| Screen / key | Before | Exact after | Reason / activation scope |
| --- | --- | --- | --- |
| PIV / protected-copy-01 | Absent (new panel) | `受保护的 PIV` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-02 | Absent (new panel) | `先选择公开签发者证书；如需签名，再选择文件；最后选择私有配置。配置仅保存在内存中，离开应用后清除。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-03 | Absent (new panel) | `选择签发者证书` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-04 | Absent (new panel) | `签发者：$it` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-05 | Absent (new panel) | `证书指纹（SHA-256）：$it` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-06 | Absent (new panel) | `此证书提供已登记的公钥和标识；不会验证证书链、有效期或吊销状态，也不会加入系统信任。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-07 | Absent (new panel) | `选择原始签名文件` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-08 | Absent (new panel) | `文件：$it · ${state.fileSize ?: 0} 字节` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-09 | Absent (new panel) | `选择私有配置 JSON` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-10 | Absent (new panel) | `打开任何文件选择器都会清除现有私有配置。不要分享私有配置或配对码。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-11 | Absent (new panel) | `配置已就绪；尚不能据此确认当前卡片身份。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-12 | Absent (new panel) | `配置中的卡片 GUID：$it` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-13 | Absent (new panel) | `受保护操作` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-14 | Absent (new panel) | `槽位` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-15 | Absent (new panel) | `CSR 主题（例如 CN=CardKey）` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-16 | Absent (new panel) | `首次扫描只核对卡片身份、槽位和 PIN 剩余次数，不会发送 PIN。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-17 | Absent (new panel) | `识别卡片并读取` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-18 | Absent (new panel) | `首次扫描已完成。请先移开卡片，再输入 PIN 并确认第二次扫描。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-19 | Absent (new panel) | `待执行：${pendingAction} · 槽位 ${pendingSlot}` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-20 | Absent (new panel) | `PIN 剩余次数：$it` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-21 | Absent (new panel) | `PIN 仅剩 1 次机会。再次输错可能锁定 PIN；恢复需要接触式连接。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-22 | Absent (new panel) | `PIN` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-23 | Absent (new panel) | `PIN 为 6–8 位数字。输错会减少剩余次数；结果不明时请勿重复执行。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-24 | Absent (new panel) | `确认执行…` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-25 | Absent (new panel) | `清除配置并取消待执行操作` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-26 | Absent (new panel) | `修改 PIN／PUK、管理密钥、生成或导入密钥、重置、CA 证书导入、CMS 和 TLS 仍需接触式连接。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-27 | Absent (new panel) | `确认第二次扫描` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-28 | Absent (new panel) | `执行 ${pendingAction}，槽位 ${pendingSlot}。再次扫描同一张卡；身份、槽位或剩余次数有变化时会停止。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-29 | Absent (new panel) | `这次会发送 PIN。输错会减少剩余次数；连接中断可能使结果无法确认。不要自动重试。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-30 | Absent (new panel) | `仅剩 1 次机会；输错后可能需要恢复 PIN。` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-31 | Absent (new panel) | `开始第二次扫描` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-32 | Absent (new panel) | `取消` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-33 | Absent (new panel) | `状态` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-34 | Absent (new panel) | `槽位与证书` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-35 | Absent (new panel) | `导出 SSH 公钥` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-36 | Absent (new panel) | `验证 PIN` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-37 | Absent (new panel) | `导出 CSR` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-38 | Absent (new panel) | `原始文件签名` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-39 | Absent (new panel) | `验证 ECDH` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-copy-40 | Absent (new panel) | `验证 RSA 解密` | State the available action, its prerequisite or consequence; protected panel only. |
| PIV / protected-status-pin-label | Absent (new protected status result) | `PIN 状态` | Identify the authenticated PIN status; protected status result only. |
| PIV / protected-status-contact-recovery | Absent (new protected status result) | `需要接触式连接恢复` | State the required recovery path when the authenticated card reports blocked NFC PIN verification; protected status result only. |
| PIV / protected-status-verified | Absent (new protected status result) | `当前会话已验证` | Limit verification to the authenticated current session; protected status result only. |
| PIV / protected-status-unverified | Absent (new protected status result) | `尚未验证` | Avoid claiming PIN verification from a data-free status read; protected status result only. |
| PIV / protected-status-retries-label | Absent (new protected status result) | `本次认证读取的 PIN 剩余重试次数` | Bind retry facts to this authenticated read; protected status result only. |
| PIV / protected-status-retries-value | Absent (new protected status result) | `${pin.retriesRemaining} 次` | Display the actual authenticated retry count without substituting zero; protected status result only. |
| PIV / protected-inspection | Absent (new protected unknown recovery) | `上次操作结果未知。请重新导入原卡片的配置，再读取受保护状态；不要重复执行原操作。` | Preserve uncertainty on the original card and require explicit authenticated status before any new private operation; protected panel during inspection only. |

## Integration copy corrections

| Screen/key | Before | After | Reason and activation scope |
| --- | --- | --- | --- |
| Protected CSR/certificate save success | Generic Activity document flow could lose its result during recreation | Existing `document_saved`: `文件已保存。` | Use the captured document save outcome for CSR/certificate bytes; do not label these documents as signatures. Protected CSR/certificate result only, after confirmed output write. |
| Protected RAW/SSH/CSR/certificate save cancellation or uncertainty | Activity-local save ownership could be lost during recreation | Existing `signing_exportCancelled`: `已取消导出。`; `signing_exportUnknown`: `未确认导出结果。请检查所选文件。` | Preserve cancellation and uncertain write outcomes in the existing captured ViewModel export path. Do not claim a saved file without successful output completion. |
| Protected signing file metadata | `文件：file · ${state.fileSize ?: 0} 字节` | `文件：$it · ${state.fileSize ?: 0} 字节`, where `$it` is the selected provider's `DISPLAY_NAME`, limited to 255 characters with control characters removed; omit this row if no nonblank name is available | Let the user distinguish the actual selected file, including same-size files. Preserve the existing format and actual byte count; do not invent a generic filename or use the document URI as a filename. Protected signing panel only. |
| Protected slot selector | `槽位` | `受保护槽位` | Distinguishes the protected selection from the ordinary PIV form on the same screen. Uses every existing supported PIV slot. |
| Protected final retry | `PIN 仅剩 1 次机会。再次输错将锁定 NFC PIN 验证，需通过接触式连接恢复。` | `PIN 仅剩 1 次机会。再次输错可能锁定 PIN；恢复需要接触式连接。` | The dedicated path also supports known contact; avoid describing all attempts as NFC. |
| Protected confirmation retry | `仅剩 1 次机会；输错后需通过接触式连接恢复。` | `仅剩 1 次机会；输错后可能需要恢复 PIN。` | Preserve the retry risk without misclassifying current transport. |
| Protected authenticated destructive final retry | No warning in this panel | Existing `confirm_lastRetryErasesKeys`, with PIN/PUK substitution: `PIN 仅剩一次重试，PUK 已锁定。此次验证失败将清除 PIV 私钥。确认继续？` | Display in pending state and explicit confirmation when authenticated PIN remaining is 1 and PUK remaining is 0. Fresh execution rereads both counters before PIN. |

## Public-name display

Reviewed with plain-language-iso-24495 before application. The labels and trust explanation remain unchanged. Only the public dynamic display values change in this candidate's protected PIV panel; this does not activate an installed application or change the issuer certificate, authenticated identity, certificate bytes, fingerprint or file contents.

| Screen / key | Before | Exact after | Reason / activation scope |
| --- | --- | --- | --- |
| Protected PIV / signing-file display name | Provider name was truncated to 255 UTF-16 code units, then ISO controls were removed; format and bidi controls remained, and the limit could split a surrogate pair. Display template: `文件：$it · ${state.fileSize ?: 0} 字节`. | Remove Unicode control and format code points and unpaired surrogates, then retain at most 255 complete code points. Omit a missing or blank cleaned name as before. Template unchanged: `文件：$it · ${state.fileSize ?: 0} 字节`. | Prevent invisible controls from changing the displayed name's order and retain complete supplementary characters. Applies to the selected signing file's public display name only; file bytes and URI are unchanged. |
| Protected PIV / issuer display | Raw issuer subject in `签发者：$it`, without a code-point display limit. | Apply the same removal and 255-code-point limit only when rendering the subject. Template unchanged: `签发者：$it`. | Bound the displayed public name and remove invisible direction/format controls while preserving ordinary non-ASCII letters and symbols. The stored subject, issuer key/identifier and certificate fingerprint remain unchanged. |

Example: a provider name containing `contract` + U+202E + U+2066 + U+2069 + U+200B + U+061C + a newline + `.pdf` displays as `contract.pdf`. A name with 254 `a` code points followed by U+1F512 retains the complete supplementary character as its 255th code point. Combining marks and ordinary right-to-left letters are retained; the limit counts code points, not grapheme clusters.

## Locale resources

The protected panel, its action titles, authenticated PIN status/retry rows and shared PIV INFO/SLOT/CSR result rows now use resources at presentation. Chinese defaults and all risk wording remain exact. English preserves trust limits, retry/erasure effects, uncertain outcomes and the contact-only boundary. The complete exact Chinese-before/default-after and English-after table is in `UI-COPY-REVIEW.md`, under “Protected PIV English localization”. English file size uses `B`; the authenticated-attempt row uses a bare count because its label states the unit.

Result rows retain stable English semantic keys for technical grouping; resources are resolved only by the UI. Related existing success/failure, export and verification resources also gain English, which applies to their shared callers. Unrelated application navigation and diagnostic controls remain in their existing language. This is source activation only, without installation, card operations or deployment. The separately reviewed empty-issuer and ZWJ/ZWNJ display rules below are preserved.
## Empty issuer display and joiners

Reviewed with plain-language-iso-24495 before application. These display rules apply when this source is built and installed; they do not establish installation or physical-card acceptance.

| Screen / key | Before | Exact after | Reason / activation scope |
| --- | --- | --- | --- |
| Protected PIV / issuer subject filtered to empty or blank | A non-null subject could leave the label alone: “签发者：”. | Omit the issuer-subject row when its cleaned display value is blank. Nonblank subjects keep “签发者：$it”. | Match the existing filename display rule without inventing a name. The stored issuer subject, certificate fingerprint and operation eligibility are unchanged. |

The existing format-character filter removes ZWJ (U+200D) and ZWNJ (U+200C). Joined emoji can therefore appear as separate emoji, and joining controls used in scripts such as Persian and Hindi are lost in these public display values. For example, “👩” + U+200D + “🔬” displays as “👩🔬”. Ordinary letters and combining marks remain. Line separator U+2028 and paragraph separator U+2029 remain outside this filter. These are display-only changes; the underlying subject, file bytes and authenticated identity are unchanged.
