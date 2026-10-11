package cc.cardkey.provider

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class HttpsAssetLinksFetcherTest {
    @Test fun fetchesOnlyTheFixedHttpsPathWithNoCredentialsAndClosesBody() {
        val body = TestBody("[]".toByteArray())
        val factory = TestCalls { request -> response(request, body = body) }
        assertArrayEquals("[]".toByteArray(), HttpsAssetLinksFetcher(factory).fetch("example.com"))
        assertEquals(1, factory.requests.size)
        val request = factory.requests.single()
        assertEquals("https://example.com/.well-known/assetlinks.json", request.url.toString())
        assertEquals("GET", request.method)
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("Proxy-Authorization"))
        assertTrue(body.closed)
    }

    @Test fun rejectsRedirectAuthenticationAndOtherNon200ResponsesWithoutAnotherCall() {
        for (code in listOf(301, 302, 307, 308, 401, 403, 404, 500)) {
            val body = TestBody("[]".toByteArray())
            val factory = TestCalls { request -> response(request, code, body).newBuilder()
                .header("Location", "https://attacker.example/assetlinks.json").build() }
            assertThrows(CallerVerificationException::class.java) { HttpsAssetLinksFetcher(factory).fetch("example.com") }
            assertEquals(1, factory.requests.size)
            assertTrue(body.closed)
        }
    }

    @Test fun rejectsWrongMimeTypeAndMissingMimeType() {
        for (type in listOf(null, "text/html", "text/plain", "application/octet-stream")) {
            val body = TestBody("[]".toByteArray(), type)
            val factory = TestCalls { response(it, body = body) }
            assertThrows(CallerVerificationException::class.java) { HttpsAssetLinksFetcher(factory).fetch("example.com") }
            assertTrue(body.closed)
        }
    }

    @Test fun rejectsOversizedDeclaredAndStreamingBodiesAndAllowsExactLimit() {
        val oversizedDeclared = TestBody(byteArrayOf(), declaredLength = StrictJson.MAX_BYTES.toLong() + 1)
        val oversizedStreaming = TestBody(ByteArray(StrictJson.MAX_BYTES + 1), declaredLength = -1)
        for (body in listOf(oversizedDeclared, oversizedStreaming)) {
            val factory = TestCalls { response(it, body = body) }
            assertThrows(CallerVerificationException::class.java) { HttpsAssetLinksFetcher(factory).fetch("example.com") }
            assertTrue(body.closed)
        }
        val exact = ByteArray(StrictJson.MAX_BYTES)
        val factory = TestCalls { response(it, body = TestBody(exact, declaredLength = -1)) }
        assertArrayEquals(exact, HttpsAssetLinksFetcher(factory).fetch("example.com"))
    }

    @Test fun tlsAndTimeoutFailuresTerminateTheRequestWithoutRetry() {
        for (failure in listOf(SSLHandshakeException("synthetic certificate rejection"), SocketTimeoutException("synthetic deadline"))) {
            val factory = TestCalls { throw failure }
            assertThrows(IOException::class.java) { HttpsAssetLinksFetcher(factory).fetch("example.com") }
            assertEquals(1, factory.requests.size)
        }
    }

    @Test fun bodyReadFailureClosesTheResponseAndCannotReturnPartialDal() {
        var closed = false
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            private val broken = object : ForwardingSource(Buffer().writeUtf8("[")) {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("synthetic truncated body")
                override fun close() { closed = true; super.close() }
            }.buffer()
            override fun source() = broken
        }
        val factory = TestCalls { response(it, body = body) }
        assertThrows(IOException::class.java) { HttpsAssetLinksFetcher(factory).fetch("example.com") }
        assertTrue(closed)
    }

    @Test fun invalidRpCannotChangeDestinationOrIssueAnyCall() {
        val factory = TestCalls { throw AssertionError("Invalid RP must not reach transport") }
        for (rp in listOf("example.com/path", "example.com@evil.com", "example.com:8443", "example.com#fragment")) {
            assertThrows(ProviderRequestException::class.java) { HttpsAssetLinksFetcher(factory).fetch(rp) }
        }
        assertTrue(factory.requests.isEmpty())
    }

    @Test fun productionClientUsesPlatformTlsAndBoundedIsolatedHttpPolicy() {
        val client = HttpsAssetLinksFetcher.secureClient()
        val defaults = OkHttpClient()
        assertSame(defaults.hostnameVerifier, client.hostnameVerifier)
        assertEquals(defaults.connectionSpecs, client.connectionSpecs)
        assertNotNull(client.x509TrustManager)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(5000, client.callTimeoutMillis)
        assertEquals(3000, client.connectTimeoutMillis)
        assertEquals(3000, client.readTimeoutMillis)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
    }

    private fun response(request: Request, code: Int = 200, body: ResponseBody): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body).build()

    private class TestBody(
        bytes: ByteArray,
        private val mediaType: String? = "application/json",
        private val declaredLength: Long = bytes.size.toLong(),
    ) : ResponseBody() {
        var closed = false
        private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType() = mediaType?.toMediaType()
        override fun contentLength() = declaredLength
        override fun source(): BufferedSource = buffered
    }

    private class TestCalls(private val execute: (Request) -> Response) : Call.Factory {
        val requests = mutableListOf<Request>()
        override fun newCall(request: Request): Call {
            requests += request
            return object : Call {
                private var executed = false
                private var cancelled = false
                override fun request() = request
                override fun execute(): Response { executed = true; return execute(request) }
                override fun enqueue(responseCallback: Callback) = throw AssertionError("Synchronous DAL transport expected")
                override fun cancel() { cancelled = true }
                override fun isExecuted() = executed
                override fun isCanceled() = cancelled
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = throw AssertionError("No request cloning expected")
            }
        }
    }
}
