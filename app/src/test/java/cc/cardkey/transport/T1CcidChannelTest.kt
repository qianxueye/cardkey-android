package cc.cardkey.transport

import cc.cardkey.protocol.Apdu
import cc.cardkey.protocol.CancellableCardChannel
import cc.cardkey.protocol.CardSession
import cc.cardkey.protocol.Dispatch
import cc.cardkey.protocol.Effect
import cc.cardkey.protocol.FailureCode
import cc.cardkey.protocol.OperationResult
import cc.cardkey.protocol.TransportException
import org.junit.Assert.*
import org.junit.Test

class T1CcidChannelTest {
    private val descriptor = byteArrayOf(9, 4, 0, 0, 3, 0x0b, 0, 0, 0) + hex(
        "36211001000703000000c0120000803e00000067320000c027090000fc0000000000000000000000ba0001000f010000ffff00000001")
    private val parameters = hex("1810009500fe00")
    private val command = hex("00200000")
    private val success = hex("9000")

    @Test fun actualDescriptorAndCurrentParametersReachApduWithoutPowerCommands() {
        val io = FakeIo(listOf(block(0, success)))
        val channel = ready(io)
        assertEquals(65544, channel.maxCommandBytes)
        assertArrayEquals(success, channel.transmit(command))
        assertEquals(listOf(0x65, 0x6c, 0x6f, 0x6f, 0x6f), io.writes.map { it[0].toInt() and 255 })
        assertArrayEquals(hex("00c000c0"), io.payloads()[0])
        assertArrayEquals(hex("00c10120e0"), io.writes[3].copyOfRange(10, io.writes[3].size))
        assertArrayEquals(hex("0000040020000024"), io.payloads()[1])
        assertTrue(io.writes.all { it[8] == 0.toByte() && it[9] == 0.toByte() })
    }

    @Test fun inactiveCardValidatesActualAtrAndCurrentParametersThenSynchronizes() {
        val io = FakeIo(listOf(block(0, success)), inactive = true)
        val channel = CcidChannel(io, CcidCapabilities.parse(descriptor, 0), 64, 100)
        channel.initialize(true)
        assertArrayEquals(success, channel.transmit(command))
        assertEquals(listOf(0x65, 0x62, 0x6c, 0x6f, 0x6f, 0x6f), io.writes.map { it[0].toInt() and 255 })
    }

    @Test fun invalidOrT0CurrentParametersNeverDispatchAnApdu() {
        val invalid = listOf(
            parameters.copyOf(6), ByteArray(7), parameters.copyOf().also { it[0] = 0x17 },
            parameters.copyOf().also { it[1] = 0 }, parameters.copyOf().also { it[1] = 0x14 },
            parameters.copyOf().also { it[3] = 0xa5.toByte() },
            parameters.copyOf().also { it[4] = 4 }, parameters.copyOf().also { it[5] = 0 },
            parameters.copyOf().also { it[5] = 0xff.toByte() }, parameters.copyOf().also { it[6] = 1 },
        )
        for (params in invalid) {
            val io = FakeIo(emptyList(), params = params)
            failure { ready(io) }
            assertTrue(io.payloads().isEmpty())
            assertEquals(1, io.closes)
        }
        val io = FakeIo(emptyList(), protocol = 0, params = ByteArray(5))
        failure { ready(io) }
        assertTrue(io.payloads().isEmpty())
    }

    @Test fun resyncMustSucceedBeforeApplicationDispatch() {
        for (reply in listOf(block(0, success), block(0xe0, byteArrayOf(1)), hex("00e00000"))) {
            val io = FakeIo(emptyList(), syncReply = reply)
            failure { ready(io) }
            assertEquals(1, io.payloads().size)
            assertArrayEquals(hex("00c000c0"), io.payloads().single())
        }
    }

    @Test fun ifsc254Splits261ByteApduAndReceiveIfsdRemains32() {
        val apdu = ByteArray(261) { it.toByte() }
        val io = FakeIo(listOf(block(0x90), block(0x20, ByteArray(32) { 7 }), block(0x40, success)))
        val response = ready(io).transmit(apdu)
        assertArrayEquals(ByteArray(32) { 7 } + success, response)
        val sent = io.payloads().drop(1)
        assertEquals(listOf(258, 11, 4), sent.map { it.size })
        assertEquals(listOf(0x20, 0x40, 0x90), sent.map { it[1].toInt() and 255 })
        assertArrayEquals(apdu, sent.take(2).flatMap { it.drop(3).dropLast(1) }.toByteArray())
        assertTrue(io.writes.all { it.size <= 271 })
    }

    @Test fun extendedApduAndMaximumResponseFloorsSurviveBlockChaining() {
        val apdu = ByteArray(65544) { (it % 251).toByte() }
        val replies = mutableListOf<ByteArray>()
        val commandBlocks = (apdu.size + 253) / 254
        repeat(commandBlocks - 1) { replies += block(0x80 or (((it + 1) and 1) shl 4)) }
        val expected = ByteArray(65538) { (it % 239).toByte() }
        expected.asList().chunked(32).forEachIndexed { index, bytes ->
            replies += block(((index and 1) shl 6) or if ((index + 1) * 32 < expected.size) 0x20 else 0, bytes.toByteArray())
        }
        val io = FakeIo(replies)
        assertArrayEquals(expected, ready(io).transmit(apdu))
        val sent = io.payloads().drop(1).take(commandBlocks)
        assertArrayEquals(apdu, sent.flatMap { it.drop(3).dropLast(1) }.toByteArray())
        assertTrue(io.writes.all { it.size <= 271 })
    }

    @Test fun sequenceNumbersPersistAcrossApdusAndDuplicateReplyIsNotAppended() {
        val io = FakeIo(listOf(block(0x20, hex("1234")), block(0x20, hex("1234")),
            block(0x40, success), block(0, success)))
        val channel = ready(io)
        assertArrayEquals(hex("12349000"), channel.transmit(command))
        assertArrayEquals(success, channel.transmit(command))
        assertEquals(listOf(0xc0, 0, 0x90, 0x90, 0x40), io.payloads().map { it[1].toInt() and 255 })
    }

    @Test fun rBlockRetransmissionIsIdenticalAndDoesNotRestartApdu() {
        val apdu = ByteArray(261) { it.toByte() }
        val io = FakeIo(listOf(block(0x81), block(0x90), block(0x92), block(0, success)))
        assertArrayEquals(success, ready(io).transmit(apdu))
        val sent = io.payloads().drop(1)
        assertArrayEquals(sent[0], sent[1])
        assertArrayEquals(sent[2], sent[3])
        assertEquals(listOf(0x20, 0x20, 0x40, 0x40), sent.map { it[1].toInt() and 255 })
    }

    @Test fun badEdcUsesRErrorAndDoesNotRepeatApplicationBlock() {
        val io = FakeIo(listOf(block(0, success).also { it[it.lastIndex] = 0 }, block(0, success)))
        assertArrayEquals(success, ready(io).transmit(command))
        assertArrayEquals(hex("00810081"), io.payloads().last())
        assertEquals(1, io.payloads().count { it[1] == 0.toByte() })
    }

    @Test fun malformedBlocksUseBoundedOtherErrorRecovery() {
        val invalid = listOf(block(0, ByteArray(33)), block(1, success), block(0x83),
            block(0xc3, byteArrayOf(0)), block(0xc3, byteArrayOf(60)),
            block(0xc1, byteArrayOf(0)), block(0xc1, byteArrayOf(0xff.toByte())),
            block(0, success).copyOf(4), byteArrayOf(0, 0),
            hex("010002900093"))
        for (bad in invalid) {
            val io = FakeIo(List(4) { bad })
            val channel = ready(io)
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.transmit(command) }.dispatch)
            assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.transmit(command) }.dispatch)
            assertEquals(1, io.closes)
            assertEquals(5, io.payloads().size)
            assertTrue(io.payloads().drop(2).all { it.contentEquals(block(0x82)) })
        }
    }

    @Test fun wtxAndIfsRequestsAreAcknowledgedWithoutIncreasingIfsd() {
        val io = FakeIo(listOf(block(0xc3, byteArrayOf(3)), block(0xc1, byteArrayOf(16)),
            block(0, success), block(0x80), block(0x40, success)))
        val channel = ready(io)
        assertArrayEquals(success, channel.transmit(command))
        assertArrayEquals(success, channel.transmit(ByteArray(20)))
        val sent = io.payloads()
        assertArrayEquals(block(0xe3, byteArrayOf(3)), sent[2])
        assertEquals(3, io.writes[5][7].toInt())
        assertArrayEquals(block(0xe1, byteArrayOf(16)), sent[3])
        assertEquals(16, sent[4][2].toInt())
        assertEquals(4, sent[5][2].toInt())
    }

    @Test fun repeatedWtxAndRetransmissionRequestsAreBounded() {
        for ((reply, count) in listOf(block(0xc3, byteArrayOf(1)) to 9, block(0x80) to 4)) {
            val io = FakeIo(List(count) { reply })
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { ready(io).transmit(command) }.dispatch)
            assertEquals(count + 1, io.payloads().size)
            assertEquals(1, io.closes)
        }
    }

    @Test fun unexpectedResyncAbortOrSupervisoryResponseNeverRestartsApdu() {
        for (pcb in listOf(0xc0, 0xe0, 0xe2, 0xe1, 0xe3)) {
            val io = FakeIo(listOf(block(pcb, if (pcb in listOf(0xe1, 0xe3)) byteArrayOf(1) else byteArrayOf())))
            val session = CardSession(ready(io))
            val result = session.run { it.exchange(Apdu(ins = 0x20, le = null), effectful = true) }
            assertTrue(result is OperationResult.Failed)
            assertEquals(Effect.UNKNOWN, (result as OperationResult.Failed).effect)
            assertEquals(2, io.payloads().size)
            assertEquals(1, io.closes)
        }
    }

    @Test fun deadlineSurvivesWtxAndCcidTimeExtensions() {
        for (ccidExtension in listOf(false, true)) {
            var clock = 0L
            val io = FakeIo(listOf(block(0xc3, byteArrayOf(3))))
            val channel = ready(io, { clock })
            io.afterRead = { clock += 101_000_000L }
            io.ccidExtension = ccidExtension
            failure { channel.transmit(command) }
            assertEquals(2, io.payloads().size)
            assertEquals(1, io.closes)
        }
    }

    @Test fun removalTimeoutAndUncertainWritesPreserveUnknownMutationAndClose() {
        for (mode in listOf("removed", "timeout", "negative", "partial")) {
            val io = FakeIo(emptyList())
            val channel = ready(io)
            when (mode) {
                "removed" -> io.removed = true
                "negative" -> io.writeResult = -1
                "partial" -> io.writeResult = 1
            }
            val result = CardSession(channel).run { it.exchange(Apdu(ins = 0x20), effectful = true) }
            assertTrue(result is OperationResult.Failed)
            assertEquals(Effect.UNKNOWN, (result as OperationResult.Failed).effect)
            assertEquals(2, io.payloads().size)
            assertEquals(1, io.closes)
        }
    }

    @Test fun zeroWriteAndCancellationBeforeDispatchDoNotChangeEffect() {
        for (cancel in listOf(false, true)) {
            val io = FakeIo(emptyList())
            val channel = CancellableCardChannel(ready(io)) { cancel }
            io.writeResult = 0
            val result = CardSession(channel).run { it.exchange(Apdu(ins = 0x20), effectful = true) }
            assertEquals(Effect.NONE, (result as OperationResult.Failed).effect)
            assertEquals(Dispatch.NOT_DISPATCHED, result.dispatch)
            if (cancel) assertEquals(FailureCode.CANCELLED, result.code)
            assertEquals(if (cancel) 1 else 2, io.payloads().size)
        }
    }

    @Test fun cancellationBetweenResponseBlocksClosesWithoutAnotherBlock() {
        val io = FakeIo(listOf(block(0x20, byteArrayOf(1))))
        val channel = CancellableCardChannel(ready(io)) { false }
        io.afterRead = { channel.cancel() }
        val result = CardSession(channel).run { it.exchange(Apdu(ins = 0x20), effectful = true) } as OperationResult.Failed
        assertEquals(FailureCode.CANCELLED, result.code)
        assertEquals(Effect.UNKNOWN, result.effect)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, result.dispatch)
        assertEquals(2, io.payloads().size)
        assertEquals(1, io.closes)
    }

    @Test fun abortIsAcknowledgedThenPoisonsSessionWithoutApduReplay() {
        val io = FakeIo(listOf(block(0xc2), byteArrayOf()))
        val channel = ready(io)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { channel.transmit(command) }.dispatch)
        assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.transmit(command) }.dispatch)
        assertArrayEquals(block(0xe2), io.payloads().last())
        assertEquals(3, io.payloads().size)
        assertEquals(1, io.closes)
    }

    @Test fun allCwiNibbleValuesAndDirectInverseConventionsAreAccepted() {
        for (cwi in 0..15) for (convention in listOf(0x10, 0x12)) {
            val params = parameters.copyOf().also { it[1] = convention.toByte(); it[3] = (0x90 or cwi).toByte() }
            val io = FakeIo(listOf(block(0, success)), params = params)
            assertArrayEquals(success, ready(io).transmit(command))
        }
    }

    @Test fun negotiatedCrcUsesIndependentPinnedReferenceVectors() {
        val params = parameters.copyOf().also { it[1] = 0x11 }
        val io = FakeIo(listOf(hex("00c30102dc15"), hex("00000290009c6d"),
            hex("00400290008ada")), params = params, syncReply = hex("00e000d0aa"))
        val channel = ready(io)
        assertArrayEquals(success, channel.transmit(hex("00a40400")))
        assertArrayEquals(success, channel.transmit(command))
        assertArrayEquals(hex("00c000f399"), io.payloads()[0])
        assertArrayEquals(hex("00000400a404005549"), io.payloads()[1])
        assertArrayEquals(hex("00e30102df2e"), io.payloads()[2])
    }

    @Test fun negotiatedCrcRejectsLrcAndSendsFullCrcRError() {
        val io = FakeIo(listOf(block(0, success), hex("00000290009c6d")),
            params = parameters.copyOf().also { it[1] = 0x11 }, syncReply = hex("00e000d0aa"))
        assertArrayEquals(success, ready(io).transmit(command))
        assertArrayEquals(hex("008200864f"), io.payloads().last())
    }

    @Test fun oversizeApduNeverDispatchesAndOversizeResponsePoisonsSession() {
        val io = FakeIo(emptyList())
        val channel = ready(io)
        assertEquals(Dispatch.NOT_DISPATCHED, failure { channel.transmit(ByteArray(65545)) }.dispatch)
        assertEquals(1, io.payloads().size)
        val replies = List(2049) { index -> block(((index and 1) shl 6) or 0x20, ByteArray(32)) }
        val overflow = FakeIo(replies)
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { ready(overflow).transmit(command) }.dispatch)
        assertEquals(1, overflow.closes)
    }

    @Test fun wrongCommandAckOrEarlyResponseDoesNotSendRemainingCommand() {
        for (reply in listOf(block(0x91), block(0, success))) {
            val io = FakeIo(listOf(reply))
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { ready(io).transmit(ByteArray(261)) }.dispatch)
            assertEquals(2, io.payloads().size)
        }
        val io = FakeIo(listOf(block(0x90)))
        failure { ready(io).transmit(command) }
        assertEquals(2, io.payloads().size)
    }

    @Test fun tpduCcidChainParameterMustRemainZero() {
        val io = FakeIo(listOf(block(0, success)))
        val channel = ready(io)
        io.chainParameter = 1
        failure { channel.transmit(command) }
        assertEquals(2, io.payloads().size)
    }

    @Test fun receivingRBlockRetransmitsOnlyTheLastResponseAcknowledgement() {
        val io = FakeIo(listOf(block(0x20, hex("1234")), block(0x81), block(0xc3, byteArrayOf(2)),
            block(0x82), block(0x40, success)))
        assertArrayEquals(hex("12349000"), ready(io).transmit(command))
        val sent = io.payloads()
        assertArrayEquals(block(0x90), sent[2])
        assertArrayEquals(sent[2], sent[3])
        assertArrayEquals(sent[2], sent[5])
        assertEquals(1, sent.count { it[1] == 0.toByte() })
    }

    @Test fun smallerIfscCannotRebuildAnOutstandingBlockWithDifferentData() {
        val io = FakeIo(listOf(block(0xc1, byteArrayOf(16)), block(0x80)))
        assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure { ready(io).transmit(ByteArray(32)) }.dispatch)
        assertEquals(3, io.payloads().size)
        assertArrayEquals(block(0xe1, byteArrayOf(16)), io.payloads().last())
    }

    @Test fun initializationRequiresExactIfsd32Acknowledgement() {
        for (reply in listOf(block(0xe1, byteArrayOf(252.toByte())), block(0xe0), block(0xc1, byteArrayOf(32)),
            block(0xe1, byteArrayOf(32)).also { it[it.lastIndex] = 0 })) {
            val io = FakeIo(emptyList(), ifsReply = reply)
            failure { ready(io) }
            assertEquals(4, io.writes.size)
            assertEquals(1, io.closes)
        }
    }

    private fun ready(io: FakeIo, clock: () -> Long = { 0L }) =
        CcidChannel(io, CcidCapabilities.parse(descriptor, 0), 64, 100, clock).also { it.initialize(false) }

    private fun failure(action: () -> Unit): TransportException {
        try { action(); fail("Expected failure") } catch (error: TransportException) { return error }
        throw AssertionError()
    }

    private inner class FakeIo(
        blocks: List<ByteArray>,
        private val params: ByteArray = parameters,
        private val protocol: Int = 1,
        private val syncReply: ByteArray = block(0xe0),
        private val inactive: Boolean = false,
        private val ifsReply: ByteArray? = null,
    ) : CcidIo {
        val writes = mutableListOf<ByteArray>()
        private val blocks = ArrayDeque(blocks)
        var closes = 0
        var writeResult: Int? = null
        var removed = false
        var ccidExtension = false
        var chainParameter = 0
        var afterRead: () -> Unit = {}
        // Initialization IFS has separate exact-frame assertions; retain RESYNCH in these traces.
        fun payloads() = writes.filter { it[0] == 0x6f.toByte() }.map { it.copyOfRange(10, it.size) }
            .filterIndexed { index, _ -> index != 1 }
        override fun write(message: ByteArray, timeoutMillis: Int): Int {
            assertTrue(timeoutMillis in 1..100)
            writes += message.copyOf()
            return writeResult ?: message.size
        }
        override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
            assertTrue(timeoutMillis in 1..100)
            val sent = writes.last()
            val type = sent[0].toInt() and 255
            val data = when (type) {
                0x65 -> byteArrayOf()
                0x62 -> hex("3bfa1800008131fe954a546178436f7265563162")
                0x6c -> params
                else -> when (writes.count { it[0] == 0x6f.toByte() }) {
                    1 -> syncReply
                    2 -> ifsReply ?: if (params[1].toInt() and 1 != 0) hex("00e101206886") else block(0xe1, byteArrayOf(32))
                    else -> if (removed || ccidExtension) byteArrayOf() else blocks.removeFirstOrNull() ?: return -1
                }
            }
            val frame = ByteArray(10 + data.size)
            frame[0] = when (type) { 0x65 -> 0x81; 0x6c -> 0x82; else -> 0x80 }.toByte()
            for (i in 0..3) frame[1 + i] = (data.size ushr (8 * i)).toByte()
            frame[6] = sent[6]
            frame[7] = when { removed -> 2; ccidExtension -> 0x80; inactive && type == 0x65 -> 1; else -> 0 }.toByte()
            frame[9] = (if (type == 0x6c) protocol else chainParameter).toByte()
            data.copyInto(frame, 10)
            frame.copyInto(buffer)
            afterRead()
            return frame.size
        }
        override fun close() { closes++ }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun block(pcb: Int, data: ByteArray = byteArrayOf()): ByteArray {
        val body = byteArrayOf(0, pcb.toByte(), data.size.toByte()) + data
        return body + body.fold(0) { acc, byte -> acc xor (byte.toInt() and 255) }.toByte()
    }
}
