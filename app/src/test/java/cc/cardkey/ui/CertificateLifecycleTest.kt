package cc.cardkey.ui

import cc.cardkey.R
import cc.cardkey.enrollment.EnrollmentCard
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.PivTlv
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import java.util.Date

internal class CertificateLifecycleFixture(val start: Long = 1767225600000L, val end: Long = start + 86400000L) {
    val card = EnrollmentCard()
    val der = JcaX509v3CertificateBuilder(X500Name("CN=Lifecycle issuer"), BigInteger("80ff", 16),
        Date(start), Date(end), X500Name("CN=Lifecycle subject"), card.keyPair.public)
        .build(JcaContentSignerBuilder("SHA256withECDSA").build(card.keyPair.private)).encoded
    init { card.certificate = der }
    fun read(now: () -> Long, slot: Int = 0x9d): OperationResult<ManagerReport> = CardSession(card).run {
        ManagerOperations(now = now).execute(it, OperationInput(ManagerOperation.PIV_SLOT, slot = slot))
    }
}

class CertificateLifecycleTest {
    @Test fun actualSlotReadAssessesInclusiveDatesAndPreservesCertificateAndKeyFacts() {
        val start = 1767225600000L
        val end = start + 86400000L
        val cases = listOf(Triple(start, end, start - 1) to "not_yet_valid",
            Triple(start, end, start) to "valid", Triple(start, end, start + 12345) to "valid",
            Triple(start, end, end) to "valid", Triple(start, end, end + 1) to "expired",
            Triple(start, start, start) to "valid", Triple(end, start, start) to "invalid_dates")
        for ((dates, expected) in cases) {
            val fixture = CertificateLifecycleFixture(dates.first, dates.second)
            var reads = 0
            val result = fixture.read({ reads++; dates.third }) as OperationResult.Completed
            val report = result.value
            val rows = report.rows.associate { it.label to it.value }
            assertEquals(1, reads)
            assertEquals(expected, rows["piv_date_status"])
            assertEquals("9D", rows["piv_slot"])
            assertEquals("CN=Lifecycle subject", rows["piv_certificate"])
            assertEquals("CN=Lifecycle issuer", rows["piv_issuer"])
            assertEquals("80FF", rows["piv_certificate_serial"])
            assertEquals(Instant.ofEpochMilli(dates.first).toString(), rows["piv_not_before"])
            assertEquals(Instant.ofEpochMilli(dates.second).toString(), rows["piv_not_after"])
            assertEquals(Instant.ofEpochMilli(dates.third).toString(), rows["piv_assessed_at"])
            assertEquals(Base64.getEncoder().encodeToString(fixture.card.keyPair.public.encoded), rows["piv_public_key"])
            assertEquals(emptyList<Int>(), report.messages)
            assertArrayEquals(fixture.der, report.export)
            assertEquals("piv-9d.cer", report.fileName)
            assertEquals(Effect.NONE, result.effect)
            assertEquals(listOf(0xa4, 0xf7, 0xcb), fixture.card.commands.map { it.ins })
            assertEquals(0x9d, fixture.card.commands[1].p2)
            assertArrayEquals("5c035fc10b".hexBytes(), fixture.card.commands[2].data)
        }
    }

    @Test fun inspectionSnapshotDoesNotChangeWhenClockOrNextSlotChanges() {
        val fixture = CertificateLifecycleFixture()
        var clock = fixture.start + 1
        var reads = 0
        val first = (fixture.read({ reads++; clock }) as OperationResult.Completed).value
        clock = fixture.end + 1
        val second = (fixture.read({ reads++; clock }, slot = 0x9a) as OperationResult.Completed).value
        assertEquals(2, reads)
        assertEquals("valid", first.rows.single { it.label == "piv_date_status" }.value)
        assertEquals("2026-01-01T00:00:00.001Z", first.rows.single { it.label == "piv_assessed_at" }.value)
        assertEquals("9D", first.rows.single { it.label == "piv_slot" }.value)
        assertEquals("expired", second.rows.single { it.label == "piv_date_status" }.value)
        assertEquals("9A", second.rows.single { it.label == "piv_slot" }.value)
        assertEquals("piv-9d.cer", first.fileName)
        assertArrayEquals(fixture.der, first.export)
    }

    @Test fun absentMalformedUnsupportedAndMissingMetadataRemainDistinct() {
        for (mode in listOf("absent", "malformed", "unsupported", "metadataAbsent")) {
            val fixture = CertificateLifecycleFixture()
            if (mode == "absent") fixture.card.certificate = null
            if (mode == "malformed") fixture.card.certificate = byteArrayOf(0x30, 0)
            val channel = object : CardChannel by fixture.card {
                override fun transmit(command: ByteArray): ByteArray {
                    val response = fixture.card.transmit(command)
                    val instruction = command[1].toInt() and 255
                    return when {
                        mode == "metadataAbsent" && instruction == 0xf7 -> "6a88".hexBytes()
                        mode == "unsupported" && instruction == 0xcb -> PivTlv.encode(0x53,
                            PivTlv.encode(0x70, fixture.der) + PivTlv.encode(0x71, byteArrayOf(1))) + "9000".hexBytes()
                        else -> response
                    }
                }
            }
            val result = CardSession(channel).run {
                ManagerOperations(now = { fixture.start }).execute(it, OperationInput(ManagerOperation.PIV_SLOT))
            }
            if (mode == "malformed" || mode == "unsupported") {
                assertTrue(result is OperationResult.Failed)
                assertEquals(if (mode == "malformed") FailureCode.MALFORMED_RESPONSE else FailureCode.UNSUPPORTED,
                    (result as OperationResult.Failed).code)
            } else {
                val report = (result as OperationResult.Completed).value
                val rows = report.rows.associate { it.label to it.value }
                if (mode == "absent") {
                    assertEquals("absent", rows["piv_certificate"])
                    assertFalse(rows.containsKey("piv_date_status"))
                    assertFalse(rows.containsKey("piv_issuer"))
                    assertNull(report.export)
                    assertTrue(report.messages.isEmpty())
                } else {
                    assertEquals("valid", rows["piv_date_status"])
                    assertFalse(rows.containsKey("piv_public_key"))
                    assertFalse(rows.containsKey("piv_algorithm"))
                    assertArrayEquals(fixture.der, report.export)
                    assertEquals(emptyList<Int>(), report.messages)
                }
            }
            assertEquals(listOf(0xa4, 0xf7, 0xcb), fixture.card.commands.map { it.ins })
        }
    }
}
