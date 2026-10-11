package cc.cardkey.ui

import org.junit.Assert.*
import org.junit.Test

class PendingOperationTest {
    @Test fun confirmationDoesNotDispatchBeforeApprovalAndConsumesApprovalOnce() {
        val input = OperationInput(ManagerOperation.PIV_CHANGE_PIN, "123456".toCharArray(), "654321".toCharArray())
        val request = PendingOperation(input)
        var calls = 0
        assertEquals(0, calls)
        assertTrue(request.dispatch { actual -> assertSame(input, actual); calls++ })
        assertFalse(request.dispatch { calls++ })
        assertEquals(1, calls)
        input.close()
    }

    @Test fun dismissingConfirmationErasesSecretsAndPreventsLateApproval() {
        val input = OperationInput(ManagerOperation.PIV_ADMIN, adminKey = ByteArray(24) { 1 }, newAdminKey = ByteArray(32) { 2 })
        val request = PendingOperation(input)
        request.close()
        assertFalse(request.dispatch { fail("Cancelled confirmation dispatched a command") })
        assertTrue(input.adminKey.all { it == 0.toByte() })
        assertTrue(input.newAdminKey.all { it == 0.toByte() })
    }

    @Test fun transferFailureDoesNotRetainOrRetrySecrets() {
        val input = OperationInput(ManagerOperation.FIDO_DELETE, "123456".toCharArray())
        val request = PendingOperation(input)
        try { request.dispatch { throw IllegalStateException() }; fail("Expected failed handoff") }
        catch (_: IllegalStateException) { }
        assertTrue(input.pin.all { it == '\u0000' })
        assertFalse(request.dispatch { fail("Failed handoff retried") })
    }
    @Test fun cancellingSecretOrBlobOperationWipesInputsWithoutDispatch() {
        val plaintext = byteArrayOf(1, 2, 3)
        val salts = ByteArray(32) { 7 }
        val blob = PendingOperation(OperationInput(ManagerOperation.FIDO_BLOB_WRITE, bytes = plaintext))
        val secret = PendingOperation(OperationInput(ManagerOperation.FIDO_ASSERT,
            assertionExtensions = cc.cardkey.protocol.fido.AssertionExtensions(hmacSalts = salts)))
        blob.close(); secret.close()
        assertArrayEquals(ByteArray(3), plaintext)
        assertArrayEquals(ByteArray(32), salts)
        assertFalse(blob.dispatch { fail("Cancelled blob operation dispatched") })
        assertFalse(secret.dispatch { fail("Cancelled secret operation dispatched") })
    }

}
