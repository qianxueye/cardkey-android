package cc.cardkey.ui

import cc.cardkey.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ManagerOperationsTest {
    @Test fun fidoInformationWorkflowSelectsBeforeReading() {
        val requests = mutableListOf<ByteArray>()
        val channel = object : CardChannel {
            override val medium = Medium.CONTACT
            override val maxCommandBytes = 261
            override fun transmit(command: ByteArray): ByteArray {
                requests += command.copyOf()
                return if (requests.size == 1) "FIDO_2_0".toByteArray() + "9000".hexBytes()
                else "00a30181684649444f5f325f3003500000000000000000000000000000000004a162726bf59000".hexBytes()
            }
            override fun close() = Unit
        }
        val result = CardSession(channel).run { ManagerOperations().execute(it, OperationInput(ManagerOperation.FIDO_INFO)) }
        assertTrue(result is OperationResult.Completed)
        val report = (result as OperationResult.Completed).value
        assertEquals("FIDO_2_0", report.rows.first { it.label == "版本" }.value)
        assertEquals(0xa4, requests.first()[1].toInt() and 255)
        assertArrayEquals("80100000010400".hexBytes(), requests.last())
    }

    @Test fun managerDoesNotSelectPivBeforeSensitiveTransportGuard() {
        var count = 0
        val channel = object : CardChannel {
            override val medium = Medium.NFC
            override val maxCommandBytes = 261
            override fun transmit(command: ByteArray): ByteArray { count++; error("No APDU expected") }
            override fun close() = Unit
        }
        val input = OperationInput(ManagerOperation.PIV_CHANGE_PIN, "123456".toCharArray(), "654321".toCharArray())
        val result = input.use { CardSession(channel).run { ManagerOperations().execute(it, input) } }
        assertEquals(FailureCode.CONTACT_REQUIRED, (result as OperationResult.Failed).code)
        assertEquals(0, count)
        assertTrue(input.pin.all { it == '\u0000' })
        assertTrue(input.newPin.all { it == '\u0000' })
    }
}
