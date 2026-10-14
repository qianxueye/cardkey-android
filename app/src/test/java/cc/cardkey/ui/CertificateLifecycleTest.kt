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
        val cases = listOf(Triple(start, end, start - 1) to "尚未生效",
            Triple(start, end, start) to "有效期内", Triple(start, end, start + 12345) to "有效期内",
            Triple(start, end, end) to "有效期内", Triple(start, end, end + 1) to "已过期",
            Triple(start, start, start) to "有效期内", Triple(end, start, start) to "有效期起止顺序异常")
        for ((dates, expected) in cases) {
            val fixture = CertificateLifecycleFixture(dates.first, dates.second)
            var reads = 0
            val result = fixture.read({ reads++; dates.third }) as OperationResult.Completed
            val report = result.value
            val rows = report.rows.associate { it.label to it.value }
            assertEquals(1, reads)
            assertEquals(expected, rows["日期状态"])
            assertEquals("9D", rows["槽位"])
            assertEquals("CN=Lifecycle subject", rows["证书"])
            assertEquals("CN=Lifecycle issuer", rows["签发者"])
            assertEquals("80FF", rows["证书序列号（十六进制）"])
            assertEquals(Instant.ofEpochMilli(dates.first).toString(), rows["生效时间（UTC）"])
            assertEquals(Instant.ofEpochMilli(dates.second).toString(), rows["到期时间（UTC）"])
            assertEquals(Instant.ofEpochMilli(dates.third).toString(), rows["检查时间（设备时钟，UTC）"])
            assertEquals(Base64.getEncoder().encodeToString(fixture.card.keyPair.public.encoded), rows["公钥"])
            assertEquals(listOf(R.string.certificate_dateScope), report.messages)
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
        assertEquals("有效期内", first.rows.single { it.label == "日期状态" }.value)
        assertEquals("2026-01-01T00:00:00.001Z", first.rows.single { it.label == "检查时间（设备时钟，UTC）" }.value)
        assertEquals("9D", first.rows.single { it.label == "槽位" }.value)
        assertEquals("已过期", second.rows.single { it.label == "日期状态" }.value)
        assertEquals("9A", second.rows.single { it.label == "槽位" }.value)
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
                    assertEquals("未存储证书", rows["证书"])
                    assertFalse(rows.containsKey("日期状态"))
                    assertFalse(rows.containsKey("签发者"))
                    assertNull(report.export)
                    assertTrue(report.messages.isEmpty())
                } else {
                    assertEquals("有效期内", rows["日期状态"])
                    assertFalse(rows.containsKey("公钥"))
                    assertFalse(rows.containsKey("算法"))
                    assertArrayEquals(fixture.der, report.export)
                    assertEquals(listOf(R.string.certificate_dateScope), report.messages)
                }
            }
            assertEquals(listOf(0xa4, 0xf7, 0xcb), fixture.card.commands.map { it.ins })
        }
    }
}
