package cc.cardkey.provider

import cc.cardkey.protocol.fido.Assertion
import cc.cardkey.protocol.fido.FidoUser
import org.junit.Assert.*
import org.junit.Test

class ProviderAccountLabelTest {
    private fun assertion(id: Int, name: String?, displayName: String?) = ProvidedAssertion(Assertion(
        byteArrayOf(id.toByte()), byteArrayOf(), byteArrayOf(),
        FidoUser(byteArrayOf(id.toByte()), name, displayName), null, null))

    @Test fun namedAccountsKeepActualUsernamesAndOnlyAmbiguousNamesNeedAnIdentifier() {
        val labels = providerAccountLabels(listOf(assertion(1, "alice", "Personal"), assertion(2, "bob", "Personal")))
        assertEquals(listOf("Personal", "Personal"), labels.map { it.title })
        assertEquals(listOf("alice", "bob"), labels.map { it.username })
        assertTrue(labels.all { it.credentialId == null })
    }
    @Test fun missingAndCollidingAccountNamesUseTruthfulNumbersAndCompleteCredentialIds() {
        val labels = providerAccountLabels(listOf(assertion(1, null, null), assertion(2, " ", ""),
            assertion(3, "same", "Same"), assertion(4, "same", "Same")))
        assertEquals(listOf(1, 2, 3, 4), labels.map { it.number })
        assertEquals(listOf("01", "02", "03", "04"), labels.map { it.credentialId })
        assertNull(labels[0].title)
        assertNull(labels[1].title)
        assertEquals("Same", labels[2].title)
        assertEquals("same", labels[2].username)
    }
}
