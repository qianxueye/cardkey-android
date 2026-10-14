package cc.cardkey.signing

import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature

class PivFileSigningTest {
    @Test fun managerSignsSelectedRawBytesForEveryAlgorithm() {
        for (algorithm in KeyAlgorithm.entries) {
            val card = EnrollmentCard(algorithm = algorithm)
            val original = "-----BEGIN CERTIFICATE-----\nnot a certificate; exact raw input\n-----END CERTIFICATE-----\n".toByteArray()
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = original.copyOf())
            try {
                val result = CardSession(card).run { ManagerOperations().execute(it, input) } as OperationResult.Completed
                val signature = requireNotNull(result.value.export) { "PIV_SIGN must export the verified signature of the selected bytes" }
                assertEquals("signature.bin", result.value.fileName)
                assertTrue(Signature.getInstance(algorithm.signature).run {
                    initVerify(card.keyPair.public); update(original); verify(signature)
                })
                assertEquals(1, card.commands.count { it.ins == 0x20 })
                assertEquals(1, card.commands.count { it.ins == 0x87 })
            } finally { input.close() }
            assertTrue(input.pin.all { it == '\u0000' })
            assertTrue(input.bytes.all { it == 0.toByte() })
        }
    }
    @Test fun exactLimitPassesAndInvalidSizesStopBeforePin() {
        for (size in listOf(0, 1_048_576, 1_048_577)) {
            val card = EnrollmentCard()
            val bytes = ByteArray(size) { (it % 251).toByte() }
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = bytes)
            val result = try { CardSession(card).run { ManagerOperations().execute(it, input) } } finally { input.close() }
            if (size == 1_048_576) assertTrue(result is OperationResult.Completed)
            else {
                assertEquals(FailureCode.INVALID_INPUT, (result as OperationResult.Failed).code)
                assertEquals(0, card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
            }
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test fun corruptMalformedWrongKeyAndChangedInputCannotProduceExport() {
        for (failure in listOf("corrupt", "malformed", "wrong-key", "changed-input")) {
            val card = EnrollmentCard()
            val other = EnrollmentCard()
            if (failure == "corrupt") card.corruptSignature = true
            if (failure == "wrong-key") card.slotMetadata = other.metadata()
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1, 2, 3))
            val channel = object : CardChannel by card {
                override fun transmit(command: ByteArray): ByteArray {
                    val response = card.transmit(command)
                    if (command[1].toInt() and 255 == 0x87) {
                        if (failure == "changed-input") input.bytes[0] = 7
                        if (failure == "malformed") return PivTlv.encode(0x7c, PivTlv.encode(0x82, byteArrayOf(0))) + byteArrayOf(0x90.toByte(), 0)
                    }
                    return response
                }
            }
            val result = try { CardSession(channel).run { ManagerOperations().execute(it, input) } } finally { input.close() }
            assertEquals(FailureCode.MALFORMED_RESPONSE, (result as OperationResult.Failed).code)
            assertEquals(Effect.APPLIED, result.effect)
            assertEquals(1, card.commands.count { it.ins == 0x87 })
            assertTrue(input.bytes.all { it == 0.toByte() })
        }
    }

    @Test fun emptyActiveMetadataAndMissingPublicKeyNeverUsePinOrCertificateFallback() {
        for (active in listOf(false, true)) {
            val card = EnrollmentCard()
            card.certificate = byteArrayOf(1, 2, 3)
            card.slotMetadata = PivTlv.encode(2, byteArrayOf(2, 1)) + PivTlv.encode(3, byteArrayOf(1)) +
                (if (active) PivTlv.encode(1, byteArrayOf(0x11)) else byteArrayOf())
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1))
            val result = try { CardSession(card).run { ManagerOperations().execute(it, input) } } finally { input.close() }
            val failed = result as OperationResult.Failed
            assertEquals(if (active) FailureCode.UNSUPPORTED else FailureCode.CARD_REJECTED, failed.code)
            if (!active) assertEquals(0x6a88, failed.status)
            assertEquals(0, card.commands.count { it.ins == 0x20 || it.ins == 0x87 || it.ins == 0xcb })
        }
    }

    @Test fun cancelledAndLostSignaturesNeverReturnOutputOrRetry() {
        for (cancelAfterGa in listOf(false, true)) {
            val card = EnrollmentCard()
            var cancelled = false
            val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray(), bytes = byteArrayOf(1, 2))
            val channel = object : CardChannel by card {
                override fun transmit(command: ByteArray): ByteArray {
                    val response = card.transmit(command)
                    if (command[1].toInt() and 255 == 0x87) {
                        if (!cancelAfterGa) throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                        cancelled = true
                    }
                    return response
                }
            }
            val result = try { CardSession(channel).run { ManagerOperations { cancelled }.execute(it, input) } } finally { input.close() }
            val failed = result as OperationResult.Failed
            assertEquals(if (cancelAfterGa) FailureCode.CANCELLED else FailureCode.TRANSPORT_FAILURE, failed.code)
            assertEquals(if (cancelAfterGa) Effect.APPLIED else Effect.UNKNOWN, failed.effect)
            if (!cancelAfterGa) assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failed.dispatch)
            assertEquals(1, card.commands.count { it.ins == 0x20 })
            assertEquals(1, card.commands.count { it.ins == 0x87 })
            assertTrue(input.bytes.all { it == 0.toByte() })
        }
    }

}
