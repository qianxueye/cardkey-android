package cc.cardkey.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentLifetimeTest {
    private val fixture = EnrollmentFixtures()
    private val year = 8760L * 60 * 60 * 1000
    private val skew = 300000L

    private fun validate(leaf: ByteArray, now: Long = fixture.now) = EnrollmentProfile.validate(
        listOf(leaf, fixture.intermediate), fixture.root, fixture.csr, now, fixture.now)

    @Test fun accepts8760HourLifetime() {
        val leaf = fixture.leaf(from = fixture.now, to = fixture.now + year)
        assertArrayEquals(leaf, validate(leaf).leaf)
    }

    @Test fun accepts8760HoursPlus300SecondsOfClockSkew() {
        val leaf = fixture.leaf(from = fixture.now - skew, to = fixture.now + year)
        assertArrayEquals(leaf, validate(leaf).leaf)
    }

    @Test fun rejects8760HoursPlus301SecondsWithLifetimeError() {
        val leaf = fixture.leaf(from = fixture.now - skew, to = fixture.now + year + 1000)
        val error = assertThrows(IllegalArgumentException::class.java) { validate(leaf) }
        assertEquals("Certificate lifetime exceeds 8760 hours plus 300 seconds", error.message)
    }

    @Test fun rejectsNotBefore301SecondsInTheFutureWithIssuanceTimeError() {
        val leaf = fixture.leaf(from = fixture.now + skew + 1000)
        val error = assertThrows(IllegalArgumentException::class.java) { validate(leaf) }
        assertEquals("Certificate notBefore is outside the issuance window", error.message)
    }

    @Test fun rejectsNotBefore301SecondsBeforeIssuanceWithIssuanceTimeError() {
        val leaf = fixture.leaf(from = fixture.now - skew - 1000)
        val error = assertThrows(IllegalArgumentException::class.java) { validate(leaf) }
        assertEquals("Certificate notBefore is outside the issuance window", error.message)
    }

    @Test fun rejectsNotAfterEqualToNotBeforeWithValidityOrderError() {
        val leaf = fixture.leaf(from = fixture.now, to = fixture.now)
        val error = assertThrows(IllegalArgumentException::class.java) { validate(leaf) }
        assertEquals("Certificate notAfter must be later than notBefore", error.message)
    }

    @Test fun rejectsExpiredLeafAtImportTime() {
        val leaf = fixture.leaf(to = fixture.now + 1000)
        assertThrows(java.security.cert.CertificateExpiredException::class.java) {
            validate(leaf, now = fixture.now + 2000)
        }
    }
}
