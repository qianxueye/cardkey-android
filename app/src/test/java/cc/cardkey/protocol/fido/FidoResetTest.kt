package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FidoResetTest {
    @Test fun resetAcknowledgementAndStatusUseOneSessionWithoutPinOrPreflight() {
        val statuses = listOf(
            cborMap(1 to Cbor.Array(listOf("FIDO_2_1".cbor())), 3 to ByteArray(16).cbor()),
            info(mapOf("clientPin" to false, "alwaysUv" to true, "ep" to true), extra = cborMap(
                13 to 8.cbor(), 12 to true.cbor(), 0x80 to "vendor-default".cbor())),
            info(mapOf("clientPin" to true)),
        )
        for (wire in CardWire.entries) for (status in statuses) {
            val peer = Peer(wire) { code -> when (code) {
                7 -> byteArrayOf(0)
                4 -> success(status)
                else -> error("Unexpected command $code")
            } }
            val result = reset(peer) as OperationResult.Completed
            assertEquals(Effect.APPLIED, result.effect)
            assertEquals(status, result.value.raw)
            assertEquals(status[12]?.bool(), result.value.forcePinChange)
            assertEquals(null, result.value.algorithms)
            assertEquals(listOf(7, 4), peer.commands)
            assertEquals(1, peer.closes)
            if (wire == CardWire.APDU) assertEquals(listOf(
                "00a4040008a0000006472f000100", "80100000010700", "80100000010400"), peer.apdus)
            else assertTrue(peer.apdus.isEmpty())
        }
    }

    @Test fun successStatusWithAnyBodyAcknowledgesResetButNeverReadsStatus() {
        for (wire in CardWire.entries) for (reply in listOf("00a0", "00f6", "00a10101", "00ff", "0000", "00a101")) {
            val peer = Peer(wire) { reply.hexBytes() }
            assertEquals(OperationResult.Failed(FailureCode.MALFORMED_RESPONSE, Effect.APPLIED,
                writeAccepted = true), reset(peer))
            assertEquals(listOf(7), peer.commands)
        }
    }

    @Test fun wellFormedDenialsDoNotAcceptResetOrReadStatus() {
        val denials = mapOf(0x27 to FailureCode.AUTHORIZATION_DENIED, 0x30 to FailureCode.CARD_REJECTED,
            0x2f to FailureCode.PHYSICAL_ACTION_REQUIRED)
        for (wire in CardWire.entries) for ((code, failure) in denials) for (suffix in listOf("", "a0", "a10101")) {
            val peer = Peer(wire) { byteArrayOf(code.toByte()) + suffix.hexBytes() }
            assertEquals(OperationResult.Failed(failure, Effect.NONE, code), reset(peer))
            assertEquals(listOf(7), peer.commands)
        }
    }

    @Test fun ambiguousErrorsAndMalformedRepliesLeaveResetUnknownWithoutReadback() {
        for (wire in CardWire.entries) for (reply in listOf("", "7f", "7fa0", "2d", "2da10101",
            "27ff", "30a101", "2ff6", "27a000")) {
            val peer = Peer(wire) { reply.hexBytes() }
            val result = reset(peer) as OperationResult.Failed
            assertEquals(Effect.UNKNOWN, result.effect)
            assertFalse(result.writeAccepted)
            assertEquals(listOf(7), peer.commands)
        }
    }

    @Test fun lostOrCancelledResetReplyNeverRetries() {
        for (wire in CardWire.entries) for (cancelled in listOf(false, true)) {
            val peer = Peer(wire) { throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, cancelled = cancelled) }
            assertEquals(OperationResult.Failed(if (cancelled) FailureCode.CANCELLED else FailureCode.TRANSPORT_FAILURE,
                Effect.UNKNOWN, dispatch = Dispatch.RESPONSE_UNAVAILABLE), reset(peer))
            assertEquals(listOf(7), peer.commands)
        }
    }

    @Test fun rejectedDispatchDoesNotInventAcceptedReset() {
        for (wire in CardWire.entries) {
            val peer = Peer(wire) { throw TransportException(Dispatch.NOT_DISPATCHED) }
            assertEquals(OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.NONE,
                dispatch = Dispatch.NOT_DISPATCHED), reset(peer))
        }
    }

    @Test fun acceptedResetSurvivesEveryReadbackFailureWithoutReplay() {
        val failures = listOf<() -> ByteArray>(
            { byteArrayOf(0x27) }, { byteArrayOf(0x7f) }, { byteArrayOf(0x2d) },
            { byteArrayOf() }, { "00a0".hexBytes() }, { "00ff".hexBytes() },
            { throw IOException("Disconnected") },
            { throw TransportException(Dispatch.RESPONSE_UNAVAILABLE, cancelled = true) },
            { throw TransportException(Dispatch.NOT_DISPATCHED, cancelled = true) },
        )
        for (wire in CardWire.entries) for (failure in failures) {
            val peer = Peer(wire) { code -> if (code == 7) byteArrayOf(0) else failure() }
            val result = reset(peer) as OperationResult.Failed
            assertEquals(Effect.APPLIED, result.effect)
            assertTrue(result.writeAccepted)
            assertEquals(listOf(7, 4), peer.commands)
        }
    }

    @Test fun cancelledChannelBeforeResetSendsNoReset() {
        for (wire in CardWire.entries) {
            var cancelled = false
            val peer = Peer(wire) { error("Cancelled reset dispatched") }
            val result = CardSession(CancellableCardChannel(peer) { cancelled }).run {
                val fido = FidoClient(it).apply { select() }
                cancelled = true
                fido.reset()
            }
            assertEquals(OperationResult.Failed(FailureCode.CANCELLED, Effect.NONE,
                dispatch = Dispatch.NOT_DISPATCHED), result)
            assertTrue(peer.commands.isEmpty())
        }
    }

    @Test fun cancelledAfterAcknowledgementBeforeReadbackRetainsAcceptance() {
        var cancelled = false
        val peer = Peer(CardWire.CTAP) { byteArrayOf(0) }
        val channel = object : CardChannel by peer {
            override fun exchangeCtap2(command: ByteArray): ByteArray {
                if (command.contentEquals(byteArrayOf(4))) cancelled = true
                return CancellableCardChannel(peer) { cancelled }.exchangeCtap2(command)
            }
        }
        val result = CardSession(channel).run { FidoClient(it).apply { select() }.reset() }
        assertEquals(OperationResult.Failed(FailureCode.CANCELLED, Effect.APPLIED,
            dispatch = Dispatch.NOT_DISPATCHED, writeAccepted = true), result)
        assertEquals(listOf(7), peer.commands)
    }

    @Test fun ctap1OnlyRouteDoesNotSendReset() {
        val peer = Peer(CardWire.CTAP) { error("CTAP1-only reset dispatched") }
        val channel = object : CardChannel by peer { override val supportsCtap2 = false }
        assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE),
            CardSession(channel).run { FidoClient(it).apply { select() }.reset() })
        assertTrue(peer.commands.isEmpty())
    }

    @Test fun apduInternalErrorOrTruncationCannotProveResetWasRejected() {
        for (reply in listOf("6f00", "90", "")) {
            val commands = mutableListOf<String>()
            val channel = object : CardChannel {
                override val medium = Medium.CONTACT
                override val maxCommandBytes = 261
                override fun transmit(command: ByteArray): ByteArray {
                    commands += command.hex()
                    return if (command[1] == 0xa4.toByte()) "FIDO_2_0".toByteArray() + "9000".hexBytes()
                    else reply.hexBytes()
                }
                override fun close() = Unit
            }
            val result = CardSession(channel).run { FidoClient(it).apply { select() }.reset() } as OperationResult.Failed
            assertEquals(Effect.UNKNOWN, result.effect)
            assertFalse(result.writeAccepted)
            assertEquals("80100000010700", commands.last())
            assertEquals(2, commands.size)
        }
    }

    private fun reset(peer: Peer) = CardSession(peer).run { FidoClient(it).apply { select() }.reset() }

    private class Peer(override val wire: CardWire, private val reply: (Int) -> ByteArray) : CardChannel {
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 261
        override val supportsCtap2 = true
        val commands = mutableListOf<Int>()
        val apdus = mutableListOf<String>()
        var closes = 0
        override fun transmit(command: ByteArray): ByteArray {
            apdus += command.hex()
            if (command[1] == 0xa4.toByte()) return "FIDO_2_0".toByteArray() + "9000".hexBytes()
            assertEquals("8010000001", command.copyOfRange(0, 5).hex())
            assertEquals(7, command.size)
            return exchangeCtap2(command.copyOfRange(5, 6)) + "9000".hexBytes()
        }
        override fun exchangeCtap2(command: ByteArray): ByteArray {
            assertEquals(1, command.size)
            val code = command.single().toInt() and 255
            commands += code
            return reply(code)
        }
        override fun close() { closes++ }
    }
}
