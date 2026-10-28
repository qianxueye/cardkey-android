# Android T=1 TPDU transport

The Kotlin USB channel discovers CCID interfaces independently of vendor/product identity. Discovery requires class `0x0b`, subclass/protocol zero, alternate setting zero, and one bulk endpoint in each direction with packet sizes in 1–1024 bytes. Discovery performs no USB reads; after permission, connection validates the CCID descriptor before claiming the interface or sending card commands.

Supported descriptors use CCID 1.0/1.1, one slot, T=0 and/or T=1, reader-managed short/extended APDU exchange or T=1 TPDU exchange, automatic parameter configuration and supported automatic negotiation, and a maximum message size in 271–65,554 bytes. TPDU requires T=1, maximum IFSD in 32–254 and host IFSD negotiation. Multi-slot, character-level, T=0-only TPDU and unsupported automatic-parameter profiles are rejected.

Generic channels report `UNKNOWN` medium. USB attachment, ATR, protocol and successful public reads do not classify the card medium. The existing `04e6:5810` qualification preserves CONTACT for that path; its recorded uTrust 2700R TPDU descriptor has `bcdDevice=0202`, `dwFeatures=0x100BA` and a maximum CCID message of 271 bytes. Public FIDO/PIV and explicitly enrolled protected PIV can use generic channels. Plaintext PIV PIN/PUK and administration retain their contact guard.

## Initialization and limits

Initialization reads slot status and current protocol parameters. An inactive card is powered once only when the caller has explicitly allowed initial activation; the returned ATR must agree with the current protocol. An active card is not powered or reset. The TPDU path requires T=1, a valid seven-byte parameter record, supported FI/DI, the reported LRC or CRC convention, BWI in 0–9, CWI in 0–15, clock-stop mode in 0–3, IFSC in 1–254 and NAD zero. T=0 and automatic-IFSD TPDU profiles are rejected before application dispatch.

An active card can retain protocol state from an earlier host session. Initialization therefore sends a standalone S(RESYNCH) and requires its exact response, then sends S(IFS,32) and requires an exact acknowledgement. RESYNCH alone does not reset IFSD. Neither step carries an application APDU. Failure closes the channel. No automatic power-cycle or reset retry follows.

The card's current IFSC controls outgoing block size; the recorded card offers 254. The reader descriptor's `dwMaxIFSD` controls its reception ceiling; this unit reports 252. The adapter validates a ceiling of at least 32 and explicitly negotiates 32, without increasing it. A card-originated IFS request changes IFSC only. If a request reduces IFSC below an outstanding command block, the adapter will not rebuild that block with different content to satisfy a later retransmission request.

Command and response ceilings remain 65,544 and 65,538 bytes. Block chaining carries those sizes while each CCID message fits the reader limit. The application layer retains its existing APDU chaining and response-continuation rules.

## Recovery and application effects

The engine validates NAD, PCB, LEN, EDC and block type before accepting content. Separate send and receive sequence numbers persist for the channel. It acknowledges chained replies and suppresses repeated reply blocks. A valid R-block can request retransmission of the identical outstanding I-block; during response chaining it can cause retransmission of the previous R-block. Neither operation starts an APDU again.

Malformed blocks produce bounded R-block error recovery. The transaction permits at most three error/retransmission/IFS events, eight WTX requests, and 131,100 block exchanges, all under the existing absolute CCID transaction deadline. WTX values must be 1–59; the echoed response supplies the multiplier in CCID `bBWI` and does not reset the deadline. A card S(ABORT) is acknowledged and the session closes. Unexpected supervisory responses or RESYNCH during an application exchange close the session without restarting the APDU.

Removal, cancellation, transport failure and exhausted recovery preserve the existing dispatch classification. An uncertain effectful APDU remains `Effect.UNKNOWN`; a command rejected before dispatch remains `Effect.NONE`. Closing releases transport resources without a power-off command.

## Reference and test evidence

The implementation is independently written Kotlin. No upstream runtime source was copied or translated. Protocol facts were checked against [USB-IF CCID 1.1](https://www.usb.org/sites/default/files/DWG_Smart-Card_CCID_Rev110.pdf), especially sections 3.2.1, 5.1, 6.1.4, 6.1.7, 6.2.1 and 7.4. The CWI range is also documented in [TeleTrusT SICCT 1.10, Annex A4](https://www.teletrust.de/fileadmin/_migrated/content_uploads/SICCT-Spezifikation-1.10.pdf).

Fixed synthetic CRC vectors use [libccid checksum.c at c37cf6cb42279ce9648ff7314180c866d68f9e0d](https://github.com/LudovicRousseau/CCID/blob/c37cf6cb42279ce9648ff7314180c866d68f9e0d/src/openct/checksum.c) as an independently executed oracle: reflected polynomial `0x8408`, initial `0xFFFF`, no final complement, high result byte first. Examples tested through the actual `CcidChannel` include SELECT `00000400a404005549`, success `00000290009c6d`, sequence-one success `00400290008ada`, WTX request `00c30102dc15`, and WTX response `00e30102df2e`. These are synthetic vectors, not certification fixtures. Standalone resynchronization behavior was cross-checked with [OpenCT at a4f3fcb91dcd78274dffb1b934e4a8c55dbb91a4](https://github.com/OpenSC/openct/blob/a4f3fcb91dcd78274dffb1b934e4a8c55dbb91a4/src/ifd/proto-t1.c).

The regression suite starts with the actual sanitized descriptor and cached ATR, but all CCID replies and current parameters are synthetic. It covers initialization refusal, full-size commands and replies, sequence persistence, chaining, duplicate handling, R-block recovery, IFS, WTX, abort, both EDC modes, overflow, deadlines, removal, cancellation and uncertain mutations. The actual descriptor admission test failed against the previous runtime before implementation. Existing reader-managed APDU tests remain intact.

No phone installation, current hardware GetParameters readback, physical USB/NFC exchange, provider flow, FIDO relying-party acceptance or PIV hardware operation was performed for this package. Those acceptance steps remain separate.
