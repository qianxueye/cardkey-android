package cc.cardkey.protocol

import org.junit.Assert.*
import org.junit.Test

class CardSessionTest {
    @Test fun acceptedWriteIsDistinctFromSecurityStateEffect() {
        val session = CardSession(TypedChannel())
        session.securityStateChanged()
        assertEquals(Effect.APPLIED, session.effect)
        assertFalse(session.hasAcceptedWrite)
        session.applied()
        session.securityStateUncertain()
        assertTrue(session.hasAcceptedWrite)
        session.close()
    }

    private class TypedChannel(val answer: () -> ByteArray = { byteArrayOf(0) }) : CardChannel {
        override val wire = CardWire.CTAP
        override val medium = Medium.CONTACT
        override val maxCommandBytes = 7609
        override val supportsCtap2 = true
        override val supportsCtap1 = true
        val commands = mutableListOf<Pair<Int, ByteArray>>()
        override fun transmit(command: ByteArray): ByteArray = error("APDU sent to CTAP channel")
        override fun exchangeCtap2(command: ByteArray): ByteArray { commands += 2 to command; return answer() }
        override fun exchangeCtap1(command: ByteArray): ByteArray { commands += 1 to command; return answer() }
        override fun close() = Unit
    }

    @Test fun ctapWireRejectsPivAndGlobalPlatformBeforeDispatch() {
        for (ins in listOf(0xa4, 0xcb, 0x50, 0x82)) {
            val channel = TypedChannel()
            val result = CardSession(channel).run { it.exchange(Apdu(ins = ins), effectful = true) }
            assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE), result)
            assertTrue(channel.commands.isEmpty())
        }
    }

    @Test fun nativeCtap2KeepsStatusWithoutSyntheticApduStatus() {
        val channel = TypedChannel { "00a0".hexBytes() }
        val result = CardSession(channel).run { it.exchangeCtap2(byteArrayOf(4)) } as OperationResult.Completed
        assertArrayEquals("00a0".hexBytes(), result.value)
        assertEquals(2, channel.commands.single().first)
        assertArrayEquals(byteArrayOf(4), channel.commands.single().second)
    }

    @Test fun apduCtap2RetainsEnvelopeAndResponseStatusHandling() {
        val channel = FakeChannel(mutableListOf("00a09000".hexBytes()))
        val result = CardSession(channel).run { it.exchangeCtap2(byteArrayOf(4)) } as OperationResult.Completed
        assertArrayEquals("00a0".hexBytes(), result.value)
        assertArrayEquals("80100000010400".hexBytes(), channel.commands.single())
    }

    @Test fun ctap1UsesFullExtendedHeaderForEmptyVersionAndLongAuthentication() {
        val channel = TypedChannel { "5532465f56329000".hexBytes() }
        val session = CardSession(channel)
        session.exchangeCtap1(Apdu(ins = 3))
        session.exchangeCtap1(Apdu(ins = 2, p1 = 3, data = ByteArray(320) { 7 }))
        assertArrayEquals("00030000000000".hexBytes(), channel.commands[0].second)
        assertArrayEquals("00020300000140".hexBytes() + ByteArray(320) { 7 } + byteArrayOf(0, 0), channel.commands[1].second)
        assertEquals(2, channel.commands.size)
        session.close()
    }

    @Test fun typedFailuresPreserveDispatchAndAcceptedWriteEvidence() {
        for (ctap1 in listOf(false, true)) for (dispatch in Dispatch.entries) {
            val channel = TypedChannel { throw TransportException(dispatch) }
            val result = CardSession(channel).run {
                it.applied()
                if (ctap1) it.exchangeCtap1(Apdu(ins = 1), effectful = true)
                else it.exchangeCtap2(byteArrayOf(1), effectful = true)
            } as OperationResult.Failed
            assertEquals(if (dispatch == Dispatch.NOT_DISPATCHED) Effect.APPLIED else Effect.UNKNOWN, result.effect)
            assertTrue(result.writeAccepted)
            assertEquals(dispatch, result.dispatch)
            assertEquals(1, channel.commands.size)
        }
    }

    @Test fun ctap1BridgeFailureAndMalformedRepliesLeaveWriteUnknown() {
        for (reply in listOf("6f00".hexBytes(), byteArrayOf(0))) {
            val channel = TypedChannel { reply }
            val result = CardSession(channel).run { it.exchangeCtap1(Apdu(ins = 1), effectful = true).requireSuccess() } as OperationResult.Failed
            assertEquals(Effect.UNKNOWN, result.effect)
            assertEquals(1, channel.commands.size)
        }
    }

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

    @Test fun protectedContinuationUsesPlainClaAndFidoKeepsItsProprietaryCla() {
        for ((cla, expected) in listOf(0x0c to 0x00, 0x80 to 0x80)) {
            val channel = FakeChannel(mutableListOf("01026102".hexBytes(), "03049000".hexBytes()))
            CardSession(channel).run { it.exchange(Apdu(cla = cla, ins = 0x87)) }
            assertEquals(expected, channel.commands[1][0].toInt() and 255)
            assertEquals(0xc0, channel.commands[1][1].toInt() and 255)
        }
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

    @Test fun oversizedWriteResponseRemainsUnknown() {
        val channel = FakeChannel(mutableListOf(ByteArray(65537) + "9000".hexBytes()))
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0xdb), effectful = true) }
        assertEquals(FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
        assertEquals(Effect.UNKNOWN, result.effect)
        assertEquals(1, channel.commands.size)
    }
}
