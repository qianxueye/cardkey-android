package cc.cardkey.protocol

import org.junit.Assert.*
import org.junit.Test

class CardSessionTest {
    private class FakeChannel(val answers: MutableList<Any>, override val medium: Medium = Medium.CONTACT) : CardChannel {
        override val maxCommandBytes = 261
        val commands = mutableListOf<ByteArray>()
        var closed = false
        override fun transmit(command: ByteArray): ByteArray {
            commands += command
            return when (val answer = answers.removeAt(0)) {
                is ByteArray -> answer
                is Exception -> throw answer
                else -> error("Invalid fixture")
            }
        }
        override fun close() { closed = true }
    }

    @Test fun followsGetResponseWithoutReplayingOriginalCommand() {
        val channel = FakeChannel(mutableListOf("01026102".hexBytes(), "03049000".hexBytes()))
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0xcb)).requireSuccess() }
        assertArrayEquals("01020304".hexBytes(), (result as OperationResult.Completed).value)
        assertArrayEquals("00c0000002".hexBytes(), channel.commands[1])
        assertTrue(channel.closed)
    }

    @Test fun lostWriteResponseIsUnknownAndNeverReplayed() {
        val channel = FakeChannel(mutableListOf(TransportException(Dispatch.RESPONSE_UNAVAILABLE)))
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0xdb), effectful = true) }
        assertEquals(Effect.UNKNOWN, (result as OperationResult.Failed).effect)
        assertEquals(1, channel.commands.size)
        assertTrue(channel.closed)
    }

    @Test fun notDispatchedWriteHasNoEffect() {
        val channel = FakeChannel(mutableListOf(TransportException(Dispatch.NOT_DISPATCHED)))
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0xdb), effectful = true) }
        assertEquals(Effect.NONE, (result as OperationResult.Failed).effect)
    }

    @Test fun acceptedWriteFailedReadbackPreservesAppliedEffect() {
        val channel = FakeChannel(mutableListOf("9000".hexBytes(), TransportException(Dispatch.RESPONSE_UNAVAILABLE)))
        val result = CardSession(channel).run {
            it.exchange(Apdu(ins = 0xdb), effectful = true).requireSuccess()
            it.applied()
            it.exchange(Apdu(ins = 0xcb)).requireSuccess()
        }
        assertEquals(Effect.APPLIED, (result as OperationResult.Failed).effect)
    }

    @Test fun contactGuardRunsBeforeAnyTransmission() {
        val channel = FakeChannel(mutableListOf(), Medium.NFC)
        val result = CardSession(channel).run { it.requireContact(); it.exchange(Apdu(ins = 0x20)) }
        assertEquals(FailureCode.CONTACT_REQUIRED, (result as OperationResult.Failed).code)
        assertTrue(channel.commands.isEmpty())
    }

    @Test fun commandChainingUsesShortApdus() {
        val channel = FakeChannel(mutableListOf("9000".hexBytes(), "9000".hexBytes()))
        CardSession(channel).run { it.exchange(Apdu(ins = 0xdb, data = ByteArray(300)), effectful = true) }
        assertEquals(0x10, channel.commands.first()[0].toInt())
        assertEquals(260, channel.commands.first().size)
        assertEquals(51, channel.commands.last().size)
    }

    @Test fun malformedWriteResponseRemainsUnknown() {
        val channel = FakeChannel(mutableListOf(byteArrayOf(0)))
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0xdb), effectful = true) }
        assertEquals(FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
        assertEquals(Effect.UNKNOWN, result.effect)
    }

    @Test fun physicalActionFailureReleasesSessionWithoutRetry() {
        val channel = FakeChannel(mutableListOf())
        val result = CardSession(channel).run { throw CardFailure(FailureCode.PHYSICAL_ACTION_REQUIRED) }
        assertEquals(FailureCode.PHYSICAL_ACTION_REQUIRED, (result as OperationResult.Failed).code)
        assertTrue(channel.closed)
        assertTrue(channel.commands.isEmpty())
    }

    @Test fun zeroRemainingPinRetriesAreReportedBlocked() {
        val channel = FakeChannel(mutableListOf("63c0".hexBytes()))
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0x20), effectful = true).requireSuccess() }
        assertEquals(FailureCode.PIN_BLOCKED, (result as OperationResult.Failed).code)
        assertEquals(0x63c0, result.status)
    }
}
