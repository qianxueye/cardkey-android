package cc.cardkey.enrollment

import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509CertificateHolder
import org.junit.Assert.*
import org.junit.Test

class EnrollmentProfileTest {
    private val fixture = EnrollmentFixtures()
    private fun validate(leaf: ByteArray = fixture.leaf(), chain: List<ByteArray> = listOf(leaf, fixture.intermediate), root: ByteArray = fixture.root,
                         now: Long = fixture.now, issuedAt: Long = fixture.now) = EnrollmentProfile.validate(chain, root, fixture.csr, now, issuedAt)
    private fun denied(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun validProfileAndExplicitRootAreAcceptedWithCopies() {
        val leaf = fixture.leaf()
        val bundle = validate(leaf)
        assertArrayEquals(leaf, bundle.leaf)
        bundle.leaf.fill(0); bundle.chain[0].fill(0); leaf.fill(0)
        assertNotEquals(0, bundle.leaf[0].toInt())
        validate(chain = listOf(fixture.leaf(), fixture.intermediate, fixture.root))
        assertArrayEquals(fixture.root, EnrollmentProfile.root(EnrollmentProfile.pem(fixture.root).toByteArray()))
    }
    @Test fun literalCommonNameCannotAddSubjectAttributes() {
        for (name in listOf("person, O=Injected", "literal+CN=other", "名字", " #literal ", "\\name\"")) {
            val csr = fixture.csr(name)
            EnrollmentProfile.subject(org.bouncycastle.pkcs.PKCS10CertificationRequest(csr.der).subject, name)
        }
        for (name in listOf("", " ", "line\nbreak", "x".repeat(65))) denied { fixture.csr(name) }
    }
    @Test fun immutableCsrRejectsWrongSignatureAndTrailingBytes() {
        val csr = fixture.csr
        val original = csr.der
        csr.der.fill(0); csr.spki.fill(0)
        assertArrayEquals(original, csr.der)
        denied { EnrollmentCsr(original + byteArrayOf(0), csr.cn) }
        denied { EnrollmentCsr(original.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }, csr.cn) }
        denied { EnrollmentCsr(original, "other") }
    }
    @Test fun exactIdentityKeyUsagePurposeAndCaAreRequired() {
        val badLeaves = listOf(fixture.leaf(cn = "other"), fixture.leaf(extraSubject = true), fixture.leaf(sans = true),
            fixture.leaf(key = EnrollmentFixtures.key()), fixture.leaf(usage = KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            fixture.leaf(usage = KeyUsage.keyAgreement), fixture.leaf(purposes = arrayOf(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth)),
            fixture.leaf(purposes = arrayOf(KeyPurposeId.id_kp_serverAuth)), fixture.leaf(ca = true), fixture.leaf(critical = true))
        badLeaves.forEach { denied { validate(it) } }
        for (missing in listOf("basic", "ku", "eku")) denied { validate(fixture.leaf(omit = missing)) }
    }
    @Test fun validityAndBackdatingAreBoundedAndImportChecksCurrentTime() {
        denied { validate(fixture.leaf(from = fixture.now - 301000)) }
        denied { validate(fixture.leaf(from = fixture.now + 1000)) }
        denied { validate(fixture.leaf(to = fixture.now + 86401000)) }
        denied { validate(fixture.leaf(to = fixture.now - 1)) }
        val bundle = validate()
        EnrollmentProfile.validate(bundle.chain, fixture.root, fixture.csr, fixture.now + 120000, bundle.verifiedAt)
        denied { EnrollmentProfile.validate(bundle.chain, fixture.root, fixture.csr, fixture.now + 7200000, bundle.verifiedAt) }
    }
    @Test fun pkixRejectsWrongAnchorMissingReorderedAndDuplicatePath() {
        denied { validate(root = EnrollmentFixtures().root) }
        denied { validate(chain = listOf(fixture.leaf())) }
        denied { validate(chain = listOf(fixture.leaf(), fixture.root, fixture.intermediate)) }
        denied { validate(chain = listOf(fixture.leaf(), fixture.intermediate, fixture.intermediate)) }
        denied { EnrollmentProfile.root(fixture.leaf()) }
    }
    @Test fun malformedDuplicateExtensionAndSizeAreRejected() {
        denied { EnrollmentProfile.certificate(fixture.leaf() + byteArrayOf(0)) }
        denied { EnrollmentProfile.certificate(ByteArray(16385)) }
        denied { validate(chain = List(6) { fixture.leaf() }) }
        denied { EnrollmentProfile.certificates(EnrollmentProfile.pem(fixture.root) + "trailing") }
        denied { EnrollmentProfile.certificates("-----BEGIN CERTIFICATE-----\n!!!\n-----END CERTIFICATE-----") }
        val cert = ASN1Sequence.getInstance(fixture.leaf())
        val tbs = ASN1Sequence.getInstance(cert.getObjectAt(0))
        val ext = ASN1TaggedObject.getInstance(tbs.getObjectAt(tbs.size() - 1))
        val values = ASN1Sequence.getInstance(ext, true)
        val duplicated = DERSequence(ASN1EncodableVector().apply { values.objects.toList().forEach { add(it as ASN1Encodable) }; add(values.getObjectAt(0)) })
        val changedTbs = DERSequence(ASN1EncodableVector().apply { for (i in 0 until tbs.size() - 1) add(tbs.getObjectAt(i)); add(DERTaggedObject(true, 3, duplicated)) })
        val changed = DERSequence(arrayOf(changedTbs, cert.getObjectAt(1), cert.getObjectAt(2))).encoded
        denied { EnrollmentProfile.certificate(changed) }
    }
    @Test fun endpointTokenBindingAudienceAndTimeAreChecked() {
        val ca = EnrollmentCA("https://localhost", fixture.root)
        ca.checkToken(fixture.token(), fixture.csr, fixture.now)
        for (address in listOf("http://localhost", "https://user@localhost", "https://localhost/prefix", "https://localhost?x", "https://localhost#fragment", "https://localhost/1.0/sign")) denied { EnrollmentCA(address, fixture.root) }
        val variants: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.addProperty("sub", "other") }, { it.addProperty("aud", "https://elsewhere/1.0/sign") },
            { it.remove("cnf") }, { it["cnf"].asJsonObject.addProperty("x5rt#S256", "wrong") },
            { it.addProperty("exp", fixture.now / 1000) }, { it.addProperty("exp", fixture.now / 1000 + 601) },
            { it.addProperty("iat", fixture.now / 1000 + 31) }, { it.addProperty("iat", fixture.now / 1000 + 300) },
            { it.addProperty("nbf", fixture.now / 1000 + 1) }, { it.addProperty("iat", "not a number") })
        variants.forEach { change -> denied { ca.checkToken(fixture.token(change = change), fixture.csr, fixture.now) } }
        denied { ca.checkToken(fixture.token(), fixture.csr("Synthetic", EnrollmentFixtures.key()), fixture.now) }
        denied { ca.checkToken("a".repeat(16385), fixture.csr, fixture.now) }
        val client = ca.client()
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache); assertSame(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(okhttp3.Authenticator.NONE, client.authenticator)
    }
}
