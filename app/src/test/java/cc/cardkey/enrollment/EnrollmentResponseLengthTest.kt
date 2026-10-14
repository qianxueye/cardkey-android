package cc.cardkey.enrollment

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class EnrollmentResponseLengthTest {
    private val fixture = EnrollmentFixtures()
    private val ca = EnrollmentCA("https://localhost", fixture.root)

    private fun execute(bytes: ByteArray, length: Long, codings: List<String> = emptyList(), unread: Boolean = false): Result<EnrollmentBundle> {
        val request = Request.Builder().url(ca.endpoint).build()
        val buffer = Buffer().write(bytes)
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = length
            override fun source() = buffer
        }
        var executions = 0
        val call = object : Call by OkHttpClient().newCall(request) {
            override fun execute(): Response {
                executions++
                return Response.Builder().request(request).protocol(Protocol.HTTP_2).code(201)
                    .message("Created").body(body).apply { codings.forEach { addHeader("Content-Encoding", it) } }.build()
            }
        }
        return runCatching { ca.execute(call, fixture.csr) }.also {
            assertEquals(1, executions)
            if (unread) assertEquals(bytes.size.toLong(), buffer.size)
        }
    }

    private fun certificateBody(leaf: ByteArray): ByteArray = JsonObject().apply {
        add("certChain", JsonArray().apply {
            add(EnrollmentProfile.pem(leaf))
            add(EnrollmentProfile.pem(fixture.intermediate))
        })
    }.toString().toByteArray(Charsets.UTF_8)

    @Test fun cleanShortMalformedBodyIsUnknownBeforeParsing() {
        val bytes = "{".toByteArray()
        val failure = execute(bytes, bytes.size.toLong() + 1).exceptionOrNull() as EnrollmentException
        assertEquals(IssuanceFailure.UNKNOWN, failure.failure)
    }

    @Test fun cleanShortValidBodyIsUnknownBeforeVerification() {
        val bytes = certificateBody(fixture.leaf())
        val result = execute(bytes, bytes.size.toLong() + 1)
        assertTrue("A short response must not verify", result.isFailure)
        assertEquals(IssuanceFailure.UNKNOWN, (result.exceptionOrNull() as EnrollmentException).failure)
    }

    @Test fun exactLengthValidBodyIsVerified() {
        val leaf = fixture.leaf()
        val bytes = certificateBody(leaf)
        assertArrayEquals(leaf, execute(bytes, bytes.size.toLong()).getOrThrow().leaf)
    }

    @Test fun exactAndUnknownLengthMalformedBodiesRemainInvalid() {
        val bytes = "{".toByteArray()
        for (length in listOf(bytes.size.toLong(), -1L)) {
            val failure = execute(bytes, length).exceptionOrNull() as EnrollmentException
            assertEquals(IssuanceFailure.INVALID, failure.failure)
        }
    }
    @Test fun explicitIdentityCodingIsCaseInsensitiveAndTrimmed() {
        val leaf = fixture.leaf()
        val bytes = certificateBody(leaf)
        assertArrayEquals(leaf, execute(bytes, bytes.size.toLong(), listOf("  IdEnTiTy  ")).getOrThrow().leaf)
    }

    @Test fun unexpectedEmptyCombinedAndDuplicateCodingAreUnknownWithoutReadingBody() {
        val bytes = certificateBody(fixture.leaf())
        for (codings in listOf(listOf(""), listOf("gzip"), listOf("br"), listOf("identity, gzip"),
            listOf("identity", "identity"), listOf("identity", "gzip"))) {
            val failure = execute(bytes, bytes.size.toLong(), codings, unread = true).exceptionOrNull() as EnrollmentException
            assertEquals(IssuanceFailure.UNKNOWN, failure.failure)
        }
    }

}
