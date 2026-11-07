package cc.cardkey.enrollment

import cc.cardkey.protocol.piv.KeyAlgorithm
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class EnrollmentV22ProfileTest {
    private val fixture = EnrollmentFixtures()
    private val subjectError = "Certificate subject does not match the person identity"
    private val emailError = "Certificate email SAN does not match the person email"
    private val upnError = "Certificate UPN SAN does not match the person UPN"
    private val sanError = "Certificate SAN contains an unsupported name type"
    private val kuError = "Certificate key usage does not match purpose"
    private val ekuError = "Certificate extended key usage does not match purpose"

    private fun key(algorithm: KeyAlgorithm) = if (algorithm == KeyAlgorithm.RSA2048)
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    else KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(if (algorithm == KeyAlgorithm.P256) "secp256r1" else "secp384r1"))
    }.generateKeyPair()

    private fun eku(purpose: EnrollmentPurpose, email: String): Array<KeyPurposeId> = when (purpose) {
        EnrollmentPurpose.TLS -> listOf(EnrollmentPurpose.TLS.eku, "1.3.6.1.4.1.311.20.2.2")
        EnrollmentPurpose.DOCUMENT -> listOf(EnrollmentPurpose.DOCUMENT.eku, "1.3.6.1.4.1.311.10.3.12") +
            if (email.isEmpty()) emptyList() else listOf(EnrollmentPurpose.KEY_MANAGEMENT.eku)
        EnrollmentPurpose.KEY_MANAGEMENT -> if (email.isEmpty()) emptyList() else listOf(EnrollmentPurpose.KEY_MANAGEMENT.eku)
    }.map(EnrollmentFixtures::oid).toTypedArray()

    private fun usage(purpose: EnrollmentPurpose) = when (purpose) {
        EnrollmentPurpose.TLS -> KeyUsage.digitalSignature
        EnrollmentPurpose.DOCUMENT -> KeyUsage.digitalSignature or KeyUsage.nonRepudiation
        EnrollmentPurpose.KEY_MANAGEMENT -> KeyUsage.keyEncipherment
    }

    @Test fun acceptsV22PurposeAlgorithmAndEmailTableWithUnorderedEku() {
        for (purpose in EnrollmentPurpose.entries) {
            val algorithms = when (purpose) {
                EnrollmentPurpose.TLS -> listOf(KeyAlgorithm.P256)
                EnrollmentPurpose.DOCUMENT -> KeyAlgorithm.entries
                EnrollmentPurpose.KEY_MANAGEMENT -> listOf(KeyAlgorithm.RSA2048)
            }
            for (algorithm in algorithms) for (email in listOf("", fixture.identity.email)) {
                val identity = fixture.identity.copy(email = email)
                val pair = key(algorithm)
                val request = fixture.csr(key = pair, selection = EnrollmentSelection(purpose, algorithm = algorithm), identity = identity)
                val purposes = eku(purpose, email).reversedArray()
                val names = if (purpose == EnrollmentPurpose.TLS) EnrollmentFixtures.authenticationNames(identity) else EnrollmentFixtures.emailNames(identity)
                val leaf = fixture.leaf(key = pair, identity = identity, usage = usage(purpose), purposes = purposes,
                    names = names, omit = if (purposes.isEmpty()) "eku" else null)
                assertArrayEquals("$purpose/$algorithm/email=$email", leaf,
                    EnrollmentProfile.validate(listOf(leaf, fixture.intermediate), fixture.root, request, fixture.now, fixture.now).leaf)
            }
        }
    }

    private fun rejected(expected: String, leaf: ByteArray, request: EnrollmentCsr = fixture.csr) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            EnrollmentProfile.validate(listOf(leaf, fixture.intermediate), fixture.root, request, fixture.now, fixture.now)
        }
        assertEquals(expected, error.message)
    }

    @Test fun rejectsEverySubjectAttributeAndRdnShapeWithIdentityError() {
        val identity = fixture.identity
        val subjects = listOf(
            "C=CA,O=Aperture Intelligence LLC,UID=${identity.employeeReference},CN=${identity.displayName}",
            "C=US,O=Other,UID=${identity.employeeReference},CN=${identity.displayName}",
            "C=US,O=Aperture Intelligence LLC,UID=OTHER,CN=${identity.displayName}",
            "C=US,O=Aperture Intelligence LLC,UID=${identity.employeeReference},CN=Other",
            "C=US,UID=${identity.employeeReference},CN=${identity.displayName}",
            "O=Aperture Intelligence LLC,C=US,UID=${identity.employeeReference},CN=${identity.displayName}",
            "C=US,O=Aperture Intelligence LLC,UID=${identity.employeeReference},CN=${identity.displayName},OU=Extra",
            "C=US,O=Aperture Intelligence LLC,UID=${identity.employeeReference}+CN=${identity.displayName}"
        )
        subjects.forEach { rejected(subjectError, fixture.leaf(subject = X500Name(it))) }
    }

    @Test fun acceptsDecodedPrintableStringAndUtf8SubjectValues() {
        val identity = fixture.identity.copy(displayName = "合成人")
        val subject = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.C, org.bouncycastle.asn1.DERPrintableString("US"))
            .addRDN(BCStyle.O, org.bouncycastle.asn1.DERPrintableString("Aperture Intelligence LLC"))
            .addRDN(BCStyle.UID, org.bouncycastle.asn1.DERUTF8String(identity.employeeReference))
            .addRDN(BCStyle.CN, org.bouncycastle.asn1.DERUTF8String(identity.displayName)).build()
        val request = fixture.csr(cn = identity.displayName, identity = identity)
        val leaf = fixture.leaf(identity = identity, subject = subject)
        assertArrayEquals(leaf, EnrollmentProfile.validate(listOf(leaf, fixture.intermediate), fixture.root, request, fixture.now, fixture.now).leaf)
    }

    @Test fun rejectsMissingDuplicateWrongOidWrongTypeAndMismatchedUpnWithNamedErrors() {
        val upn = EnrollmentFixtures.upn(fixture.identity.upn)
        rejected("Authentication certificate requires exactly one UPN SAN", fixture.leaf(names = emptyArray()))
        rejected("Authentication certificate requires exactly one UPN SAN", fixture.leaf(names = arrayOf(upn, upn)))
        rejected(upnError, fixture.leaf(names = arrayOf(EnrollmentFixtures.upn("other@apture.cc"))))
        rejected(upnError, fixture.leaf(names = arrayOf(EnrollmentFixtures.upn(fixture.identity.upn, oid = "1.2.3.4"))))
        rejected(upnError, fixture.leaf(names = arrayOf(EnrollmentFixtures.upn(fixture.identity.upn, utf8 = false))))
        rejected(sanError, fixture.leaf(sans = true))
    }

    @Test fun rejectsMismatchedDuplicateAndUnexpectedEmailWithEmailError() {
        val upn = EnrollmentFixtures.upn(fixture.identity.upn)
        val email = GeneralName(GeneralName.rfc822Name, fixture.identity.email)
        rejected(emailError, fixture.leaf(names = arrayOf(upn, GeneralName(GeneralName.rfc822Name, "other@apture.cc"))))
        rejected(emailError, fixture.leaf(names = arrayOf(upn, email, email)))
        rejected(emailError, fixture.leaf(), fixture.csr(identity = fixture.identity.copy(email = "")))
    }

    @Test fun rejectsMissingAdditionalAndWrongKuAndEkuForEachPurpose() {
        for (purpose in EnrollmentPurpose.entries) {
            val pair = key(EnrollmentSelection(purpose).algorithm)
            val request = fixture.csr(key = pair, selection = EnrollmentSelection(purpose))
            val purposes = eku(purpose, fixture.identity.email)
            val names = if (purpose == EnrollmentPurpose.TLS) EnrollmentFixtures.authenticationNames(fixture.identity)
                else EnrollmentFixtures.emailNames(fixture.identity)
            fun leaf(ku: Int = usage(purpose), ekus: Array<KeyPurposeId> = purposes, omit: String? = null,
                     sans: Array<GeneralName> = names) = fixture.leaf(key = pair, usage = ku, purposes = ekus, names = sans, omit = omit)
            rejected(kuError, leaf(ku = usage(purpose) or KeyUsage.keyAgreement), request)
            rejected(kuError, leaf(ku = KeyUsage.cRLSign), request)
            rejected(kuError, leaf(omit = "ku"), request)
            rejected(ekuError, leaf(ekus = purposes + KeyPurposeId.id_kp_serverAuth), request)
            rejected(ekuError, leaf(ekus = purposes.dropLast(1).toTypedArray()), request)
            rejected(ekuError, leaf(ekus = arrayOf(KeyPurposeId.anyExtendedKeyUsage)), request)
            rejected(ekuError, leaf(omit = "eku"), request)
            if (purpose != EnrollmentPurpose.TLS) {
                rejected(emailError, leaf(sans = emptyArray()), request)
                rejected(sanError, leaf(sans = names + EnrollmentFixtures.upn(fixture.identity.upn)), request)
            }
        }
    }

    @Test fun rejectsEmailSanAndEmailProtectionWhenPersonHasNoEmail() {
        for (purpose in listOf(EnrollmentPurpose.DOCUMENT, EnrollmentPurpose.KEY_MANAGEMENT)) {
            val identity = fixture.identity.copy(email = "")
            val selection = EnrollmentSelection(purpose)
            val pair = key(selection.algorithm)
            val request = fixture.csr(key = pair, selection = selection, identity = identity)
            val purposes = eku(purpose, "")
            rejected(emailError, fixture.leaf(key = pair, identity = identity, usage = usage(purpose), purposes = purposes,
                omit = if (purposes.isEmpty()) "eku" else null, names = EnrollmentFixtures.emailNames(fixture.identity)), request)
            rejected(ekuError, fixture.leaf(key = pair, identity = identity, usage = usage(purpose),
                purposes = purposes + EnrollmentFixtures.oid(EnrollmentPurpose.KEY_MANAGEMENT.eku), names = emptyArray()), request)
        }
    }

    @Test fun rejectsWrongCsrKeyAndEcKeyLabelledAsRsa2048WithNamedErrors() {
        rejected("Certificate public key does not match the CSR", fixture.leaf(key = EnrollmentFixtures.key()))
        val pair = key(KeyAlgorithm.RSA2048)
        val request = fixture.csr(key = pair, selection = EnrollmentSelection(EnrollmentPurpose.KEY_MANAGEMENT))
        rejected("Certificate public key does not match the requested algorithm", fixture.leaf(), request)
    }

    @Test fun selectionRejectsEveryWrongSlotAndNonRsaKeyManagementAlgorithm() {
        for (purpose in EnrollmentPurpose.entries) for (slot in listOf(0x9a, 0x9c, 0x9d, 0x9e)) {
            if (slot == EnrollmentSelection(purpose).slot) continue
            val error = assertThrows(IllegalArgumentException::class.java) { EnrollmentSelection(purpose, slot) }
            assertEquals("Enrollment slot or algorithm does not match purpose", error.message)
        }
        for (algorithm in listOf(KeyAlgorithm.P256, KeyAlgorithm.P384)) {
            val error = assertThrows(IllegalArgumentException::class.java) { EnrollmentSelection(EnrollmentPurpose.KEY_MANAGEMENT, algorithm = algorithm) }
            assertEquals("Enrollment slot or algorithm does not match purpose", error.message)
        }
        val tlsError = assertThrows(IllegalArgumentException::class.java) { EnrollmentSelection(algorithm = KeyAlgorithm.P384) }
        assertEquals("Enrollment slot or algorithm does not match purpose", tlsError.message)
        val error = assertThrows(IllegalArgumentException::class.java) { fixture.csr(identity = fixture.identity.copy(upn = "")) }
        assertEquals("Authentication requires a person UPN", error.message)
    }
}
