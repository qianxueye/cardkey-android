package cc.cardkey.ui

import cc.cardkey.R
import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*
import org.junit.Assert.*
import org.junit.Test

class ManagerResetOperationTest {
    @Test fun confirmedResetUsesOnlyResetAndStatusWithoutPinAndConsumesConsentOnce() {
        val channel = ManagerTestChannel(CardWire.CTAP) { code, parameters ->
            assertNull(parameters)
            when (code) { 7 -> success(); 4 -> success(info()); else -> error("Unexpected command $code") }
        }
        val input = OperationInput(ManagerOperation.FIDO_RESET, pin = "1234".toCharArray())
        val pending = PendingOperation(input)
        assertEquals(listOf(R.string.confirm_fidoReset), pending.messages)
        assertTrue(channel.commands.isEmpty())
        assertTrue(pending.dispatch { actual -> actual.use {
            val result = CardSession(channel).run { ManagerOperations().execute(it, actual) }
            assertEquals(Effect.APPLIED, (result as OperationResult.Completed).effect)
            assertEquals(listOf(R.string.operation_fidoResetReadback),
                ConfirmationPolicy.resultMessages(result, CardWire.CTAP, ManagerOperation.FIDO_RESET))
            assertNull(result.value.export)
            assertNull(result.value.secrets)
        } })
        assertFalse(pending.dispatch { error("Consent replay") })
        assertEquals(listOf(7, 4), channel.commands.map { it.first })
        assertArrayEquals(CharArray(4), input.pin)
    }

    @Test fun declinedResetConfirmationSendsNothing() {
        val channel = ManagerTestChannel(CardWire.CTAP) { _, _ -> error("Declined reset dispatched") }
        val pending = PendingOperation(OperationInput(ManagerOperation.FIDO_RESET))
        assertEquals(listOf(R.string.confirm_fidoReset), pending.messages)
        pending.close()
        assertFalse(pending.dispatch { CardSession(channel).run { session -> ManagerOperations().execute(session, it) } })
        assertTrue(channel.commands.isEmpty())
    }

    @Test fun apduNfcAndCtap1AreRejectedBeforeAnySelectionOrDispatch() {
        for (medium in Medium.entries) for (wire in CardWire.entries) {
            var commands = 0
            val channel = object : CardChannel {
                override val medium = medium
                override val wire = wire
                override val supportsCtap1 = true
                override val maxCommandBytes = 7609
                override fun transmit(command: ByteArray): ByteArray { commands++; error("Unsupported reset") }
                override fun exchangeCtap2(command: ByteArray): ByteArray { commands++; error("Unsupported reset") }
                override fun close() = Unit
            }
            assertEquals(OperationResult.Failed(FailureCode.UNSUPPORTED, Effect.NONE), CardSession(channel).run {
                ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_RESET))
            })
            assertEquals(0, commands)
        }
    }

    @Test fun lostMalformedAndFailedReadbackKeepEffectsWithoutReplayOrSuccessMessage() {
        for (scenario in listOf("lost", "empty", "trailing", "cancel", "unknown", "readback")) {
            val channel = ManagerTestChannel(CardWire.CTAP) { code, _ -> when (code) {
                7 -> when (scenario) {
                    "lost" -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                    "empty" -> byteArrayOf()
                    "trailing" -> byteArrayOf(0, 0xa0.toByte())
                    "cancel" -> byteArrayOf(0x2d)
                    "unknown" -> byteArrayOf(0x7f)
                    else -> success()
                }
                4 -> throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                else -> error("Unexpected command")
            } }
            val result = CardSession(channel).run {
                ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_RESET))
            } as OperationResult.Failed
            val accepted = scenario in listOf("trailing", "readback")
            assertEquals(accepted, result.writeAccepted)
            assertEquals(if (accepted) Effect.APPLIED else Effect.UNKNOWN, result.effect)
            assertEquals(if (scenario == "readback") listOf(7, 4) else listOf(7), channel.commands.map { it.first })
            assertFalse(ConfirmationPolicy.resultMessages(result, CardWire.CTAP, ManagerOperation.FIDO_RESET)
                .contains(R.string.operation_fidoResetReadback))
        }
    }
}
