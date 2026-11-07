package cc.cardkey.enrollment

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Integer
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class StepCaFixture : AutoCloseable {
    private val cli = requireNotNull(System.getenv("CARDKEY_STEP_CLI")) { "CARDKEY_STEP_CLI is required; real CA qualification cannot be skipped" }
    private val caBinary = requireNotNull(System.getenv("CARDKEY_STEP_CA")) { "CARDKEY_STEP_CA is required; real CA qualification cannot be skipped" }
    val directory = Files.createTempDirectory("cardkey-android-ca-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
    private val password = directory.resolve("password")
    private var server: Process? = null
    private val signingKeys = mutableMapOf<EnrollmentPurpose, java.security.PrivateKey>()
    val address: String
    val root: ByteArray
    init {
        try {
            require(run(cli, "version").contains("Smallstep CLI/0.31.0"))
            require(run(caBinary, "--version").contains("Smallstep CA/0.30.2"))
            password.writeText(UUID.randomUUID().toString())
            Files.setPosixFilePermissions(password.toPath(), PosixFilePermissions.fromString("rw-------"))
            val port = ServerSocket(0).use { it.localPort }
            address = "https://127.0.0.1:$port"
            run(cli, "ca", "init", "--name", "Synthetic Android Enrollment", "--dns", "127.0.0.1", "--address", "127.0.0.1:$port",
                "--provisioner", "fixture", "--password-file", password.path, "--provisioner-password-file", password.path,
                "--deployment-type", "standalone")
            root = directory.resolve("certs/root_ca.crt").readBytes()
            val template = directory.resolve("client.tpl").apply { writeText("""
                {{- if not .Token.sub }}{{ fail "subject required" }}{{ end -}}
                {{- if not .Token.cnf }}{{ fail "binding required" }}{{ end -}}
                {{- if not (index .Token.cnf "x5rt#S256") }}{{ fail "binding required" }}{{ end -}}
                {{- if not (typeIs "*ecdsa.PublicKey" .Insecure.CR.PublicKey) }}{{ fail "P-256 required" }}{{ end -}}
                {{- if ne .Insecure.CR.PublicKey.Curve.Params.Name "P-256" }}{{ fail "P-256 required" }}{{ end -}}
                {"subject":{"extraNames":[{"type":"2.5.4.6","value":"US"},{"type":"2.5.4.10","value":"Aperture Intelligence LLC"},{"type":"0.9.2342.19200300.100.1.1","value":{{ toJson .Token.employee_reference }}},{"type":"2.5.4.3","value":{{ toJson .Token.sub }}}]},"sans":[{"type":"userPrincipalName","value":{{ toJson .Token.upn }}}{{ if .Token.email }},{"type":"email","value":{{ toJson .Token.email }}}{{ end }}],"keyUsage":["digitalSignature"],"extKeyUsage":["clientAuth"],"unknownExtKeyUsage":["1.3.6.1.4.1.311.20.2.2"],"basicConstraints":{"isCA":false}}
            """.trimIndent()) }
            val config = directory.resolve("config/ca.json")
            run(cli, "ca", "provisioner", "add", "fixture-document", "--type", "JWK", "--create",
                "--password-file", password.path, "--ca-config", config.path)
            val documentTemplate = directory.resolve("document.tpl").apply { writeText("""
                {{- if not .Token.sub }}{{ fail "subject required" }}{{ end -}}
                {{- if not .Token.cnf }}{{ fail "binding required" }}{{ end -}}
                {{- if not (index .Token.cnf "x5rt#S256") }}{{ fail "binding required" }}{{ end -}}
                {{- if typeIs "*ecdsa.PublicKey" .Insecure.CR.PublicKey -}}
                  {{- if not (or (eq .Insecure.CR.PublicKey.Curve.Params.Name "P-256") (eq .Insecure.CR.PublicKey.Curve.Params.Name "P-384")) }}{{ fail "unsupported curve" }}{{ end -}}
                {{- else if typeIs "*rsa.PublicKey" .Insecure.CR.PublicKey -}}
                  {{- if ne .Insecure.CR.PublicKey.N.BitLen 2048 }}{{ fail "RSA-2048 required" }}{{ end -}}
                {{- else }}{{ fail "unsupported key" }}{{ end -}}
                {"subject":{"extraNames":[{"type":"2.5.4.6","value":"US"},{"type":"2.5.4.10","value":"Aperture Intelligence LLC"},{"type":"0.9.2342.19200300.100.1.1","value":{{ toJson .Token.employee_reference }}},{"type":"2.5.4.3","value":{{ toJson .Token.sub }}}]},"sans":[{{ if .Token.email }}{"type":"email","value":{{ toJson .Token.email }}}{{ end }}],"keyUsage":["digitalSignature","contentCommitment"],"extKeyUsage":[{{ if .Token.email }}"emailProtection"{{ end }}],"unknownExtKeyUsage":["1.3.6.1.5.5.7.3.36","1.3.6.1.4.1.311.10.3.12"],"basicConstraints":{"isCA":false}}
            """.trimIndent()) }
            run(cli, "ca", "provisioner", "add", "fixture-key-management", "--type", "JWK", "--create",
                "--password-file", password.path, "--ca-config", config.path)
            val managementTemplate = directory.resolve("key-management.tpl").apply { writeText("""
                {{- if not .Token.sub }}{{ fail "subject required" }}{{ end -}}
                {{- if not .Token.cnf }}{{ fail "binding required" }}{{ end -}}
                {{- if not (index .Token.cnf "x5rt#S256") }}{{ fail "binding required" }}{{ end -}}
                {{- if not (typeIs "*rsa.PublicKey" .Insecure.CR.PublicKey) }}{{ fail "RSA-2048 required" }}{{ end -}}
                {{- if ne .Insecure.CR.PublicKey.N.BitLen 2048 }}{{ fail "RSA-2048 required" }}{{ end -}}
                {"subject":{"extraNames":[{"type":"2.5.4.6","value":"US"},{"type":"2.5.4.10","value":"Aperture Intelligence LLC"},{"type":"0.9.2342.19200300.100.1.1","value":{{ toJson .Token.employee_reference }}},{"type":"2.5.4.3","value":{{ toJson .Token.sub }}}]},"sans":[{{ if .Token.email }}{"type":"email","value":{{ toJson .Token.email }}}{{ end }}],"keyUsage":["keyEncipherment"],"extKeyUsage":[{{ if .Token.email }}"emailProtection"{{ end }}],"basicConstraints":{"isCA":false}}
            """.trimIndent()) }
            val json = JsonParser.parseString(config.readText()).asJsonObject
            val provisioner = json["authority"].asJsonObject["provisioners"].asJsonArray[0].asJsonObject
            provisioner.add("claims", JsonParser.parseString("""{"minTLSCertDuration":"60s","defaultTLSCertDuration":"300s","maxTLSCertDuration":"300s","disableRenewal":true,"enableSSHCA":false}"""))
            provisioner.add("options", JsonObject().apply { add("x509", JsonObject().apply { addProperty("templateFile", template.path) }) })
            val document = json["authority"].asJsonObject["provisioners"].asJsonArray[1].asJsonObject
            document.add("claims", provisioner["claims"].deepCopy())
            document.add("options", JsonObject().apply { add("x509", JsonObject().apply { addProperty("templateFile", documentTemplate.path) }) })
            val management = json["authority"].asJsonObject["provisioners"].asJsonArray[2].asJsonObject
            management.add("claims", provisioner["claims"].deepCopy())
            management.add("options", JsonObject().apply { add("x509", JsonObject().apply { addProperty("templateFile", managementTemplate.path) }) })
            listOf(provisioner, document, management).zip(EnrollmentPurpose.entries).forEach { (role, purpose) ->
                val jwk = JsonParser.parseString(runInput(role["encryptedKey"].asString,
                    cli, "crypto", "jwe", "decrypt", "--password-file", password.path)).asJsonObject
                require(jwk["kty"].asString == "EC" && jwk["crv"].asString == "P-256")
                val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                signingKeys[purpose] = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(
                    BigInteger(1, Base64.getUrlDecoder().decode(jwk["d"].asString)), parameters.getParameterSpec(ECParameterSpec::class.java)))
            }
            json.add("logger", JsonParser.parseString("""{"format":"common"}"""))
            config.writeText(json.toString())
            server = builder(caBinary, config.path, "--password-file", password.path).redirectOutput(ProcessBuilder.Redirect.to(java.io.File("/dev/null"))).start()
            val client = EnrollmentCA(address, root).client()
            var ready = false
            repeat(100) {
                if (!ready) {
                    require(server!!.isAlive) { "Owned CA exited before health check" }
                    ready = runCatching { client.newCall(okhttp3.Request.Builder().url("$address/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
                    if (!ready) Thread.sleep(50)
                }
            }
            require(ready) { "Owned CA health timeout" }
        } catch (error: Throwable) { close(); throw error }
    }
    private fun builder(vararg command: String) = ProcessBuilder(*command).apply {
        environment()["STEPPATH"] = directory.path
        environment()["NO_COLOR"] = "1"
        redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
    }
    private fun run(vararg command: String): String = runInput(null, *command)
    private fun runInput(input: String?, vararg command: String): String {
        val output = Files.createTempFile(directory.toPath(), "output-", ".tmp").toFile()
        try {
            val process = builder(*command).redirectOutput(output).start()
            try {
                process.outputStream.use { if (input != null) it.write(input.toByteArray()) }
                require(process.waitFor(45, TimeUnit.SECONDS)) { "Fixture command timed out" }
                require(process.exitValue() == 0) { "Fixture command failed without exposing private output" }
                return output.readText()
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        } finally { output.delete() }
    }
    fun token(csr: EnrollmentCsr, purpose: EnrollmentPurpose = csr.selection.purpose): String {
        val file = directory.resolve("request.csr").apply { writeText(csr.pem()) }
        val role = when (purpose) {
            EnrollmentPurpose.TLS -> "fixture"
            EnrollmentPurpose.DOCUMENT -> "fixture-document"
            EnrollmentPurpose.KEY_MANAGEMENT -> "fixture-key-management"
        }
        val original = run(cli, "ca", "token", csr.cn, "--cnf-file", file.path, "--ca-url", address, "--root", directory.resolve("certs/root_ca.crt").path,
            "--provisioner", role, "--password-file", password.path, "--not-after", "5m").trim().split('.')
        val claims = JsonParser.parseString(String(Base64.getUrlDecoder().decode(original[1]))).asJsonObject.apply {
            addProperty("employee_reference", csr.identity.employeeReference)
            addProperty("display_name", csr.identity.displayName)
            add("sans", com.google.gson.JsonArray().apply { add(csr.cn) })
            if (csr.identity.email.isNotEmpty()) addProperty("email", csr.identity.email)
            if (purpose == EnrollmentPurpose.TLS) addProperty("upn", csr.identity.upn)
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val unsigned = original[0] + "." + encoder.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKeys.getValue(purpose)); update(unsigned.toByteArray()); sign()
        }
        val values = ASN1Sequence.getInstance(signature)
        val compact = (0..1).flatMap { index ->
            val bytes = ASN1Integer.getInstance(values.getObjectAt(index)).positiveValue.toByteArray()
            bytes.takeLast(32).let { List(32 - it.size) { 0.toByte() } + it }
        }.toByteArray()
        return unsigned + "." + encoder.encodeToString(compact)
    }

    override fun close() {
        server?.let { process ->
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            check(!process.isAlive) { "Owned CA did not terminate" }
        }
        signingKeys.clear()
        check(directory.deleteRecursively()) { "Owned CA temporary directory was not removed" }
    }
}
