package cc.cardkey.integration

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import cc.cardkey.transport.FidoHidReports
import org.junit.Assert.*
import org.junit.Test

class FramedHidResetTest {
    @Test fun resetAndReadbackStayInOneInitializedHidSessionAndLeaseAtEveryReportSize() {
        for (size in listOf(7, 8, 16, 64)) {
            val lease = requireNotNull(CardOperationGate.tryAcquire())
            lease.use {
                val peer = SyntheticHidPeer(FidoHidReports(size, size)) { code, parameters ->
                    assertNull(parameters)
                    assertNull(CardOperationGate.tryAcquire())
                    when (code) {
                        7 -> byteArrayOf(0)
                        4 -> success(info(mapOf("clientPin" to true)))
                        else -> error("Unexpected CTAP command $code")
                    }
                }
                val result = CardSession(peer.connect()).run { FidoClient(it).apply { select() }.reset() }
                assertTrue(result is OperationResult.Completed)
                assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect)
                assertEquals(listOf(7, 4), peer.commands)
                assertEquals(1, peer.initializationCount)
                assertTrue(peer.closed)
            }
            requireNotNull(CardOperationGate.tryAcquire()).close()
        }
    }

    @Test fun framedMalformedAcknowledgementPreservesAcceptanceWithoutReadback() {
        for (size in listOf(7, 8, 16, 64)) for (reply in listOf("00a0", "00f6", "00ff")) {
            val peer = SyntheticHidPeer(FidoHidReports(size, size)) { _, _ -> reply.hexBytes() }
            val result = CardSession(peer.connect()).run { FidoClient(it).apply { select() }.reset() }
            assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED,
                writeAccepted = true), result)
            assertEquals(listOf(7), peer.commands)
            assertEquals(1, peer.initializationCount)
            assertTrue(peer.closed)
        }
    }

    @Test fun framedAmbiguousResetAndAcceptedReadbackFailureHaveDistinctEffects() {
        for (size in listOf(7, 8, 16, 64)) for (accepted in listOf(false, true)) {
            val peer = SyntheticHidPeer(FidoHidReports(size, size)) { code, _ ->
                if (accepted && code == 7) byteArrayOf(0) else byteArrayOf(0x2d)
            }
            val result = CardSession(peer.connect()).run { FidoClient(it).apply { select() }.reset() }
            assertEquals(OperationResult.Failed(FailureCode.CANCELLED,
                if (accepted) Effect.APPLIED else Effect.UNKNOWN, 0x2d, writeAccepted = accepted), result)
            assertEquals(if (accepted) listOf(7, 4) else listOf(7), peer.commands)
            assertEquals(1, peer.initializationCount)
        }
    }
}
