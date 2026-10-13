# Approved HID management messages

The Owner approved HID3 and FIDO-RESET-COPY2 on 2026-10-12. This package activates the five Chinese messages in the existing Chinese Android interface. The approved English equivalents are recorded below; an English interface is outside this package. Existing APDU/NFC and PIV wording remains unchanged. No physical reset is authorized or established by these source changes.

| Resource | Chinese runtime text | Approved English equivalent |
| --- | --- | --- |
| `status_hidUserPresenceRequired` | 请触摸安全密钥或按下按键。 | Touch the security key or press its button. |
| `error_hidPhysicalActionRequired` | 请重新发起操作，再触摸安全密钥或按下按键。 | Start the operation again, then touch the security key or press its button. |
| `error_hidPowerCycleRequired` | PIN 验证暂时锁定。请断开安全密钥的所有电源，再重新连接。 | PIN verification is temporarily locked. Disconnect all power from the security key, then reconnect it. |
| `confirm_fidoReset` | 重置会使此设备上的全部 FIDO2／U2F 凭据失效，清除 FIDO PIN、相关数据和设置，无法撤销。之后需重新绑定账户。其他功能和证明配置是否保留，取决于设备。请按设备要求完成物理确认。确认重置？ | Resetting invalidates all FIDO2/U2F credentials on this device and clears its FIDO PIN, related data and settings. This cannot be undone. You will need to register the device with your accounts again. Whether other functions and attestation configuration are retained depends on the device. Complete the physical confirmation required by the device. Reset? |
| `operation_fidoResetReadback` | 设备已接受 FIDO 重置，当前状态已读取。 | The device acknowledged the FIDO reset. Its current status has been read. |

The waiting hint requires an active HID operation's current waiting state. The restart hint applies only to known physical-action failures and never replaces guidance for unknown or fenced writes. The power instruction requires a returned temporary PIN-block status or a true PIN power-cycle state; it does not clear uncertainty or prove recovery.

The reset result requires the strict reset acknowledgement and completed status readback. It does not prove deletion of every credential or retention of PIV, attestation identity or controller pairing. Missing, malformed or cancelled replies and failed readback retain the existing accepted/unknown outcome messages and mutation fences. The Credential Provider receives the HID hints; it has no reset action.
