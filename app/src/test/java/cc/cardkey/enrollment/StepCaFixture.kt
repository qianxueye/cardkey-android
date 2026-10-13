package cc.cardkey.enrollment

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class StepCaFixture : AutoCloseable {
    private val cli = requireNotNull(System.getenv("CARDKEY_STEP_CLI")) { "CARDKEY_STEP_CLI is required; real CA qualification cannot be skipped" }
    private val caBinary = requireNotNull(System.getenv("CARDKEY_STEP_CA")) { "CARDKEY_STEP_CA is required; real CA qualification cannot be skipped" }
    val directory = Files.createTempDirectory("cardkey-android-ca-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
    private val password = directory.resolve("password")
    private var server: Process? = null
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
                {"subject":{"commonName":{{ toJson .Token.sub }}},"sans":[],"keyUsage":["digitalSignature"],"extKeyUsage":["clientAuth"],"basicConstraints":{"isCA":false}}
            """.trimIndent()) }
            val config = directory.resolve("config/ca.json")
            val json = JsonParser.parseString(config.readText()).asJsonObject
            val provisioner = json["authority"].asJsonObject["provisioners"].asJsonArray[0].asJsonObject
            provisioner.add("claims", JsonParser.parseString("""{"minTLSCertDuration":"60s","defaultTLSCertDuration":"300s","maxTLSCertDuration":"300s","disableRenewal":true,"enableSSHCA":false}"""))
            provisioner.add("options", JsonObject().apply { add("x509", JsonObject().apply { addProperty("templateFile", template.path) }) })
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
    private fun run(vararg command: String): String {
        val output = Files.createTempFile(directory.toPath(), "output-", ".tmp").toFile()
        try {
            val process = builder(*command).redirectOutput(output).start()
            try {
                require(process.waitFor(45, TimeUnit.SECONDS)) { "Fixture command timed out" }
                require(process.exitValue() == 0) { "Fixture command failed without exposing private output" }
                return output.readText()
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        } finally { output.delete() }
    }
    fun token(csr: EnrollmentCsr): String {
        val file = directory.resolve("request.csr").apply { writeText(csr.pem()) }
        return run(cli, "ca", "token", csr.cn, "--cnf-file", file.path, "--ca-url", address, "--root", directory.resolve("certs/root_ca.crt").path,
            "--provisioner", "fixture", "--password-file", password.path, "--not-after", "5m").trim()
    }
    override fun close() {
        server?.let { process ->
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            check(!process.isAlive) { "Owned CA did not terminate" }
        }
        check(directory.deleteRecursively()) { "Owned CA temporary directory was not removed" }
    }
}
