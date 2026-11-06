package cc.cardkey.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentBasicConstraintsTest {
    private val fixture = EnrollmentFixtures()

    private fun validate(leaf: ByteArray) = EnrollmentProfile.validate(
        listOf(leaf, fixture.intermediate), fixture.root, fixture.csr, fixture.now, fixture.now)

    @Test fun acceptsAbsentBasicConstraintsForEndEntity() {
        val leaf = fixture.leaf(omit = "basic")
        assertArrayEquals(leaf, validate(leaf).leaf)
    }

    @Test fun acceptsExplicitCaFalseForEndEntity() {
        val leaf = fixture.leaf(ca = false)
        assertArrayEquals(leaf, validate(leaf).leaf)
    }

    @Test fun rejectsCaTrueWithEndEntityError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validate(fixture.leaf(ca = true))
        }
        assertEquals("Certificate basicConstraints must identify an end entity", error.message)
    }
}
