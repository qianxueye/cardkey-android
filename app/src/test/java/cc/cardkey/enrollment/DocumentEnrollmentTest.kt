package cc.cardkey.enrollment

import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import cc.cardkey.signing.PivCms
import cc.cardkey.tls.PivTlsIdentity
import cc.cardkey.ui.*
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.junit.Assert.*
import org.junit.Test

class DocumentEnrollmentTest {
    private val documentEku = KeyPurposeId.getInstance(ASN1ObjectIdentifier(EnrollmentPurpose.DOCUMENT.eku))
    private fun selection(algorithm: KeyAlgorithm = KeyAlgorithm.P256, slot: Int = 0x9c) =
        EnrollmentSelection(EnrollmentPurpose.DOCUMENT, slot, algorithm)
    private fun csr(card: EnrollmentCard, selection: EnrollmentSelection): EnrollmentCsr {
        val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "123456".toCharArray(), enrollmentIdentity = EnrollmentFixtures().identity.copy(displayName = "Synthetic document"),
            enrollmentSelection = selection)
        return try {
            (CardSession(card).run { ManagerOperations().execute(it, input) } as OperationResult.Completed).value.enrollmentCsr!!
        } finally { input.close() }
    }
    private fun ready(fixture: EnrollmentFixtures, csr: EnrollmentCsr) = EnrollmentWorkflow().apply {
        settings("https://localhost")
        choosePerson(enrollmentTarget("https://localhost", csr.identity))
        selection(csr.selection)
        acceptRoot(rootRequest(), fixture.root)
        settleCsr(startCsr(), csr)
    }
    @Test fun exactDocumentAlgorithmsAndProofAreRequired() {
        assertEquals(0x9c, EnrollmentSelection(EnrollmentPurpose.DOCUMENT).slot)
        for (algorithm in KeyAlgorithm.entries) {
            val card = EnrollmentCard(algorithm = algorithm)
            val request = csr(card, selection(algorithm))
            assertArrayEquals(card.keyPair.public.encoded, request.spki)
            assertTrue(card.commands.filter { it.ins == 0x87 }.all { it.p2 == 0x9c })
            assertTrue(card.commands.none { it.ins == 0x47 || it.ins == 0xdb })
            assertTrue(runCatching { EnrollmentCsr(request.der, request.identity) }.isFailure == (algorithm != KeyAlgorithm.P256))
            assertTrue(runCatching { EnrollmentCsr(request.der.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }, request.identity, request.selection) }.isFailure)
            val wrongDigest = if (algorithm == KeyAlgorithm.RSA2048) "SHA384withRSA" else if (algorithm == KeyAlgorithm.P384) "SHA256withECDSA" else "SHA384withECDSA"
            val wrong = JcaPKCS10CertificationRequestBuilder(EnrollmentProfile.name(request.cn), card.keyPair.public)
                .build(JcaContentSignerBuilder(wrongDigest).build(card.keyPair.private)).encoded
            assertTrue(runCatching { EnrollmentCsr(wrong, request.identity, request.selection) }.isFailure)
        }
        assertTrue(runCatching { EnrollmentSelection(EnrollmentPurpose.TLS, 0x9c) }.isFailure)
        assertTrue(runCatching { EnrollmentSelection(EnrollmentPurpose.TLS, algorithm = KeyAlgorithm.P384) }.isFailure)
    }
    @Test fun documentLeafRequiresExactRoleIdentityUsageValidityAndTrust() {
        val fixture = EnrollmentFixtures()
        for (algorithm in KeyAlgorithm.entries) {
            val card = EnrollmentCard(algorithm = algorithm)
            val request = csr(card, selection(algorithm))
            fun leaf(cn: String = request.cn, purposes: Array<KeyPurposeId> = arrayOf(documentEku, EnrollmentFixtures.oid("1.3.6.1.4.1.311.10.3.12"), EnrollmentFixtures.oid(EnrollmentPurpose.KEY_MANAGEMENT.eku)),
                     usage: Int = KeyUsage.digitalSignature or KeyUsage.nonRepudiation, sans: Boolean = false, ca: Boolean = false,
                     from: Long = fixture.now - 60000, to: Long = fixture.now + 3600000,
                     key: java.security.KeyPair = card.keyPair, extra: Boolean = false) =
                fixture.leaf(cn = cn, key = key, purposes = purposes, usage = usage, sans = sans, ca = ca, from = from, to = to, extraSubject = extra, identity = request.identity.copy(displayName = cn), names = EnrollmentFixtures.emailNames(request.identity))
            fun validate(bytes: ByteArray, chain: List<ByteArray> = listOf(bytes, fixture.intermediate), root: ByteArray = fixture.root) =
                EnrollmentProfile.validate(chain, root, request, fixture.now, fixture.now)
            val good = leaf()
            val bundle = validate(good)
            assertTrue(runCatching { PivTlsIdentity(EnrollmentImport(request, EnrollmentCA("https://localhost", fixture.root), bundle)) }.isFailure)
            listOf(leaf(cn = "other"), leaf(purposes = arrayOf(KeyPurposeId.id_kp_clientAuth)),
                leaf(purposes = arrayOf(documentEku, KeyPurposeId.id_kp_clientAuth)), leaf(purposes = arrayOf(KeyPurposeId.anyExtendedKeyUsage)),
                leaf(usage = KeyUsage.digitalSignature), leaf(sans = true), leaf(ca = true),
                leaf(from = fixture.now - 301000), leaf(to = fixture.now + 8760L * 3600000 + 301000), leaf(from = fixture.now + 1000),
                leaf(key = EnrollmentFixtures.key()), leaf(extra = true)).forEach { assertTrue(runCatching { validate(it) }.isFailure) }
            assertTrue(runCatching { validate(good, root = EnrollmentFixtures().root) }.isFailure)
            assertTrue(runCatching { validate(good, chain = listOf(good)) }.isFailure)
        }
    }
    @Test fun randomizedCsrCannotBypassUnknownIntentAndOtherRequestsPreserveReceipt() {
        val fixture = EnrollmentFixtures()
        val first = fixture.csr(selection = selection())
        val flow = ready(fixture, first)
        val (owner, _) = flow.prepareIssuerRequest()
        flow.pause()
        val oldReceipt = flow.state.value.unknownReceipts.single()
        flow.selection(EnrollmentSelection(EnrollmentPurpose.KEY_MANAGEMENT)); flow.selection(selection())
        val second = fixture.csr(selection = selection())
        assertNotEquals(first.binding, second.binding)
        flow.settleCsr(flow.startCsr(), second)
        assertTrue(flow.state.value.attempted)
        assertTrue(runCatching { flow.prepareIssuerRequest() }.isFailure)
        flow.settleIssuance(owner, null, EnrollmentStage.REJECTED)
        assertSame(oldReceipt, flow.state.value.unknownReceipts.single())
        flow.choosePerson(enrollmentTarget("https://localhost", first.identity.copy(displayName = "Another document")))
        val other = fixture.csr("Another document", selection = selection())
        flow.settleCsr(flow.startCsr(), other)
        val (newOwner, _) = flow.prepareIssuerRequest()
        flow.settleIssuance(newOwner, null, EnrollmentStage.REJECTED)
        assertSame(oldReceipt, flow.state.value.unknownReceipts.single())
    }
    @Test fun selectionAbaRetiresCallbacksAndPickerAndImportKeepsReplacementWarning() {
        val fixture = EnrollmentFixtures()
        val request = fixture.csr(selection = selection())
        val changes = listOf(EnrollmentSelection(EnrollmentPurpose.KEY_MANAGEMENT), selection(KeyAlgorithm.P384), EnrollmentSelection())
        for (change in changes) {
            val flow = ready(fixture, request)
            val owner = flow.generation
            val picker = flow.rootRequest()
            flow.selection(change); flow.selection(request.selection)
            flow.settleCsr(owner, request)
            assertNull(flow.state.value.csr)
            assertFalse(flow.acceptRoot(picker, fixture.root))
            assertNotEquals(owner, flow.state.value.revision)
        }
        val messages = ConfirmationPolicy.messages(OperationInput(ManagerOperation.PIV_ENROLL_IMPORT, enrollmentSelection = selection()))
        assertTrue(messages.contains(cc.cardkey.R.string.enrollment_confirmImport))
        assertTrue(runCatching { selection(slot = 0x9a) }.isFailure)
        assertTrue(runCatching { selection(slot = 0x9d) }.isFailure)
    }
    @Test fun realRoleSeparatedCaIssuesAllDocumentAlgorithmsImportsAndSignsCms() {
        StepCaFixture().use { fixture ->
            for (algorithm in KeyAlgorithm.entries) {
                val card = EnrollmentCard(algorithm = algorithm)
                val request = csr(card, selection(algorithm))
                val ca = EnrollmentCA(fixture.address, fixture.root)
                val flow = EnrollmentWorkflow().apply {
                    settings(fixture.address); choosePerson(enrollmentTarget(fixture.address, request.identity)); selection(request.selection)
                    acceptRoot(rootRequest(), fixture.root); settleCsr(startCsr(), request)
                }
                val token = fixture.token(request)
                val (owner, _) = flow.prepareIssuerRequest()
                val bundle = ca.execute(ca.call(request, token), request)
                flow.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
                val input = OperationInput(ManagerOperation.PIV_ENROLL_IMPORT, adminKey = card.managementKey.copyOf(), enrollmentImport = flow.importRequest())
                val result = try { CardSession(card).run { ManagerOperations().execute(it, input) } } finally { input.close() }
                assertTrue(result is OperationResult.Completed)
                assertArrayEquals(bundle.leaf, card.certificate)
                flow.imported(owner)
                assertTrue(runCatching { PivTlsIdentity(flow.importedIdentity()) }.isFailure)
                val content = "Synthetic document content".toByteArray()
                val signed = CardSession(card).run { PivClient(it).signCmsFile(request.selection.slot, "123456".toCharArray(), content) }
                assertTrue(signed is OperationResult.Completed)
                PivCms.verify((signed as OperationResult.Completed).value, content, EnrollmentProfile.certificate(bundle.leaf), algorithm)
                assertTrue(card.commands.none { it.ins == 0x47 })
                assertEquals(1, card.commands.count { it.ins == 0xdb })
                if (algorithm != KeyAlgorithm.P256) {
                    val wrongRoleRequest = csr(card, request.selection)
                    val wrongRoleToken = fixture.token(wrongRoleRequest, EnrollmentPurpose.TLS)
                    val failure = runCatching { ca.execute(ca.call(wrongRoleRequest, wrongRoleToken), wrongRoleRequest) }.exceptionOrNull() as EnrollmentException
                    assertEquals(IssuanceFailure.REJECTED, failure.failure)
                    assertEquals(1, card.commands.count { it.ins == 0xdb })
                }
            }
            for (purpose in listOf(EnrollmentPurpose.TLS, EnrollmentPurpose.DOCUMENT)) {
                val card = EnrollmentCard()
                val request = csr(card, if (purpose == EnrollmentPurpose.TLS) EnrollmentSelection() else selection())
                val wrongRole = if (purpose == EnrollmentPurpose.TLS) EnrollmentPurpose.DOCUMENT else EnrollmentPurpose.TLS
                val ca = EnrollmentCA(fixture.address, fixture.root)
                val token = fixture.token(request, wrongRole)
                ca.checkToken(token, request)
                val error = runCatching { ca.execute(ca.call(request, token), request) }.exceptionOrNull() as EnrollmentException
                assertEquals(IssuanceFailure.INVALID, error.failure)
                assertNull(card.certificate)
            }
        }
    }
}
