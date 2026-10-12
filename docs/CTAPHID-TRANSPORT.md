# Android CTAPHID transport

The Manager and Credential Provider can use a USB FIDO authenticator through CTAPHID. CTAP2 requests go directly to the HID CBOR command. U2F requests use the HID MSG command and standard extended APDU framing. A nonempty command has a seven-byte header and a two-byte expected-length field; the empty VERSION request is exactly seven bytes (`00 03 00 00 00 00 00`). These paths do not synthesize smart-card SELECT replies, relay pairing tickets, or carry PIV and GlobalPlatform commands. The existing NFC, CCID APDU and CCID T=1 paths remain available.

## Discovery and connection

USB discovery lists candidate HID interfaces from their interrupt endpoint shape without opening a device or transferring descriptors. A listed candidate is not yet a verified FIDO authenticator, even if Android has already granted permission. Connection runs on the operation worker and validates the report descriptor's FIDO usage page `F1D0`, usage `01`, and supported report layout before claim/INIT. Unsupported descriptors or interfaces fail before initialization.

This package supports the approved controller profile with 64-byte input and output reports. CTAP 2.1 sections 11.2.4 and 11.2.8.1 allow other vendor-defined packet sizes and require general-purpose hosts to handle their declared sizes. Those sizes are not supported here; this bounded adapter does not claim complete CTAPHID device compatibility. Supporting another report size requires separate framing, buffer and endpoint qualification.

The native adapter uses Android `UsbRequest` interrupt transfers. It does not use CCID bulk transfers for HID. INIT contains a fresh random nonce; the reply must match it and supply a valid channel ID and protocol capabilities. CTAP1 and CTAP2 availability follows those capabilities. Messages use bounded 64-byte reports, a maximum CTAPHID message length of 7,609 bytes, and the authenticator's advertised CTAP limits. Responses must match the channel, command, declared length and continuation sequence. KEEPALIVE is intermediate; CANCEL has no separate acknowledgement.

PIV controls are unavailable on HID, and the protocol session rejects APDU commands before transmission. A local route key includes the device path, interface, wire and observed attachment generation. Detach invalidates old descriptors and permission callbacks, including when a later attachment reuses the path. A USB device path, interface, serial, VID/PID or AAGUID is not proof of card identity.

## Operation ownership and outcomes

One Manager or Provider operation holds the process-wide operation lease from connection through final channel closure. PIN agreement, token acquisition, inventory, assertions and Large Blob commands share that connection. No command phase reconnects or sends a new INIT. The Provider reserves its request claim before connection, so initialization cannot precede ownership of that request.

Cancellation retires native requests and buffers before the lease can be reused. A response arriving after cancellation cannot release secret output or become a successful user result. Closing a USB connection releases its interface; it does not send a card power-off command. HID presence results do not start CCID removal probes or impose a smart-card removal requirement. Terminal HID presence failures currently use generic failure guidance. The Manager parses PIN power-cycle state but has no dedicated HID recovery instruction; it does not display the APDU/NFC card-removal sentence for HID. KEEPALIVE updates the current operation’s waiting state, fenced to its channel and attachment generation, without a separate touch/button prompt. Processing, terminal response, cancellation and disconnect clear that state. A waiting-state update is not proof of user presence or authorization.

An effectful request with a lost or malformed terminal response remains uncertain. Bridge CTAP status `7F`, U2F status `6F00`, and cancellation status `2D` after a possible effectful dispatch also preserve uncertainty. An earlier accepted write remains recorded. None of these outcomes causes automatic replay. A valid CTAPHID CHANNEL_BUSY rejection before any processing progress is a known busy failure and preserves previous effects. BUSY after KEEPALIVE, malformed errors and partial native-transfer failures remain uncertain; no retry is automatic.

After an unresolved HID write, routine inspection cannot clear the Manager's recovery fence, including inspection through a different route. An opaque USB route cannot establish that the same paired card is still present. A successful inspection is useful readback, but it does not resolve the earlier write by itself.

The controller profile under qualification uses a fixed channel ID of 1 and invalidates its paired protocol session on INIT. The app's lease and native interface ownership coordinate its own operations. They do not establish safe simultaneous use by another process, browser or management application.

## Verification boundary

Public tests use synthetic descriptors, report streams and cryptographic keys. They cover framing and reassembly, nonce and capability validation, cancellation and request retirement, session effects, both smart-card and direct CTAP routes, and signed Manager/Provider workflows. No private controller source, applet binary, pairing key, real credential or captured card secret is required by the build.

Phone USB permission behavior, actual interrupt transfers, controller presence/button behavior, disconnect/reconnect, physical power recovery, contention with other clients, Android provider selection, browser and relying-party acceptance require separate physical evidence. A software gate or a scripted signature check does not establish those results.

Protocol references: [CTAP 2.1 HID transport](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-20210615.html), [U2F extended request and VERSION framing](https://fidoalliance.org/specs/fido-u2f-v1.2-ps-20170411/fido-u2f-raw-message-formats-v1.2-ps-20170411.html#getversion-request-and-response---u2f_version), and [Android UsbRequest interrupt transfers](https://developer.android.com/reference/android/hardware/usb/UsbRequest).
