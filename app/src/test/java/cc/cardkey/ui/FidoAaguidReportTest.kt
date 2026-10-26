package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Test

class FidoAaguidReportTest {
    @Test fun actualGetInfoReportPreservesKnownAndZeroAaguidWithoutNetwork() {
        for (wire in listOf(CardWire.CTAP, CardWire.APDU)) for (raw in listOf("eabb46cce24180bfae9e96fa6d2975cf", "00000000000000000000000000000000")) {
            val channel = ManagerTestChannel(wire) { code, _ ->
                assertEquals(4, code)
                success(cborMap(1 to Cbor.Array(listOf("FIDO_2_0".cbor())), 3 to raw.hexBytes().cbor(), 4 to textMap()))
            }
            val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) } as OperationResult.Completed
            val guid = result.value.rows.first { it.label == "AAGUID" }.value
            assertEquals(raw, guid.replace("-", ""))
            assertEquals(guid, result.value.rows.single { it.fidoModel }.value)
            assertEquals(Effect.NONE, result.effect)
        }
    }
}
