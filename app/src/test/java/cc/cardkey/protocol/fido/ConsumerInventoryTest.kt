package cc.cardkey.protocol.fido

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ConsumerInventoryTest {
    private class Card(val protocols: List<Int> = listOf(2), val pinSet: Boolean = true,
                       val forced: Boolean = false, val remaining: Int = 8,
                       val powerCycle: Boolean = false, val empty: Boolean = false,
                       val failLater: Boolean = false, val badPin: Boolean = false) {
        val server = FidoClientTest.PinServer(if (2 in protocols) 2 else 1)
        val respond: (Int, Cbor.Map?) -> ByteArray = { code, map -> when (code) {
            4 -> success(info(mapOf("pinUvAuthToken" to true, "credMgmt" to true, "clientPin" to pinSet),
                protocols, extra = cborMap(12 to forced.cbor())))
            6 -> if (map!![2].number() == 1L) success(cborMap(3 to remaining.cbor(), 4 to powerCycle.cbor()))
                else if (badPin && map[2].number() == 9L) byteArrayOf(0x31)
                else server.respond(map, 4, null)
            10 -> when (map!![1].number()) {
                1L -> success(cborMap(1 to (if (empty) 0 else 2).cbor(), 2 to 8.cbor()))
                2L -> if (empty) byteArrayOf(0x2e) else success(cborMap(3 to textMap("id" to "first.test".cbor()),
                    4 to sha256("first.test".toByteArray()).cbor(), 5 to 2.cbor()))
                3L -> success(cborMap(3 to textMap("id" to "second.test".cbor()), 4 to sha256("second.test".toByteArray()).cbor()))
                4L -> {
                    val second = map[2].map()[1].bytes().contentEquals(sha256("second.test".toByteArray()))
                    if (second && failLater) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    success(cborMap(6 to FidoUser(byteArrayOf(7), "account", null).encode(),
                        7 to descriptor(byteArrayOf(if (second) 2 else 1)), 8 to cborMap(), 9 to 1.cbor()))
                }
                else -> error("Unexpected inventory command")
            }
            else -> error("Unexpected command")
        } }
        val channel = FidoChannel(respond)
        fun run(approve: () -> Boolean = { false }): OperationResult<CredentialInventory> {
            val pin = "1234".toCharArray()
            return CardSession(channel).run { FidoClient(it).apply { select() }.inventory(pin, approve) }
                .also { assertArrayEquals(CharArray(4), pin) }
        }
    }

    @Test fun completeInventoryUsesOneFreshInfoAndOneTokenForBothAdvertisedProtocols() {
        for (protocols in listOf(listOf(1), listOf(1, 2))) {
            val card = Card(protocols)
            val result = card.run() as OperationResult.Completed
            assertEquals(listOf("first.test", "second.test"), result.value.sites.map { it.rp.id })
            assertEquals(2, result.value.sites.sumOf { it.credentials.size })
            assertEquals(1, card.server.tokenRequests)
            assertEquals(1, card.channel.commands.count { it.first == 4 })
            assertEquals(listOf(1L, 2L, 3L, 4L, 4L), card.channel.commands.filter { it.first == 10 }.map { it.second!![1].number() })
        }
    }
    @Test fun unsupportedUnsetForcedBlockedAndPowerCycleStopBeforeAuthentication() {
        val cases = listOf(Card(protocols = listOf(3)) to FailureCode.UNSUPPORTED,
            Card(pinSet = false) to FailureCode.PIN_SETUP_REQUIRED,
            Card(forced = true) to FailureCode.PIN_CHANGE_REQUIRED,
            Card(remaining = 0) to FailureCode.PIN_BLOCKED,
            Card(powerCycle = true) to FailureCode.PHYSICAL_ACTION_REQUIRED)
        for ((card, expected) in cases) {
            assertEquals(expected, (card.run() as OperationResult.Failed).code)
            assertEquals(0, card.server.tokenRequests)
            assertFalse(card.channel.commands.any { it.first == 10 })
        }
    }
    @Test fun finalAttemptDeclineDoesNotAuthenticateAndAcceptanceAllowsExactlyOne() {
        var prompts = 0
        val declined = Card(remaining = 1)
        assertEquals(FailureCode.CANCELLED, (declined.run { prompts++; false } as OperationResult.Failed).code)
        assertEquals(0, declined.server.tokenRequests)
        val accepted = Card(remaining = 1)
        assertTrue(accepted.run { prompts++; true } is OperationResult.Completed)
        assertEquals(1, accepted.server.tokenRequests)
        assertEquals(2, prompts)
        assertEquals(2, accepted.channel.commands.count { it.first == 6 && it.second!![2].number() == 1L })
    }
    @Test fun emptySuccessAndLaterFailureAreDifferentAndWrongPinIsNotRetried() {
        val empty = Card(empty = true)
        assertTrue((empty.run() as OperationResult.Completed).value.sites.isEmpty())
        val partial = Card(failLater = true)
        assertTrue(partial.run() is OperationResult.Failed)
        assertEquals(1, partial.server.tokenRequests)
        val wrong = Card(badPin = true)
        assertEquals(FailureCode.PIN_INVALID, (wrong.run() as OperationResult.Failed).code)
        assertEquals(1, wrong.channel.commands.count { it.first == 6 && it.second!![2].number() == 9L })
    }
    @Test fun powerCyclePreflightUsesTheActualTransportRecoveryWithoutAuthentication() {
        for (wire in CardWire.entries) {
            val card = Card(powerCycle = true)
            val direct = object : CardChannel {
                override val wire = CardWire.CTAP
                override val supportsCtap2 = true
                override val medium = Medium.CONTACT
                override val maxCommandBytes = 4096
                override fun transmit(command: ByteArray): ByteArray = error("Unexpected APDU")
                override fun exchangeCtap2(command: ByteArray): ByteArray = card.respond(command[0].toInt() and 255,
                    if (command.size == 1) null else CborCodec.decode(command.copyOfRange(1, command.size)).map())
                override fun close() = Unit
            }
            val pin = "1234".toCharArray()
            val result = CardSession(if (wire == CardWire.CTAP) direct else card.channel).run {
                FidoClient(it).apply { select() }.inventory(pin)
            } as OperationResult.Failed
            assertEquals(if (wire == CardWire.CTAP) FailureCode.PIN_BLOCKED else FailureCode.PHYSICAL_ACTION_REQUIRED, result.code)
            assertEquals(0x34, result.status)
            assertEquals(0, card.server.tokenRequests)
            assertArrayEquals(CharArray(4), pin)
        }
    }

}
