package cc.cardkey.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FidoModelCatalogTest {
    private val known = "eabb46cc-e241-80bf-ae9e-96fa6d2975cf"
    private val other = "12345678-1234-1234-1234-123456789abc"
    private val json = """{"no":291,"$known":{"friendlyNames":{"en-US":"TOKEN2 PIN Plus Security Key Series ","zh":"系列","zh-CN":"中国系列"}},"$other":{"friendlyNames":{"en-US":"Synthetic Model"}}}"""
    private fun response(code: Int = 200, body: String = json, retry: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://fixture.invalid/").build()).protocol(Protocol.HTTP_1_1)
        .code(code).message("fixture").header("Cache-Control", "max-age=300").apply { if (retry != null) header("Retry-After", retry) }
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun namesAreGenericLocalizedAndTrimmed() = runTest {
        val catalog = FidoModelCatalog({ response() }, scope = this)
        assertEquals(FidoModelName.Known("中国系列"), catalog.lookup(known, "zh-CN"))
        assertEquals(FidoModelName.Known("系列"), catalog.lookup(known, "zh-TW"))
        assertEquals(FidoModelName.Known("TOKEN2 PIN Plus Security Key Series"), catalog.lookup(known, "fr-FR"))
        assertEquals(FidoModelName.Known("Synthetic Model"), catalog.lookup(other, "en-US"))
        assertEquals(FidoModelName.Missing, catalog.lookup("00000000-0000-0000-0000-000000000000", "en-US"))
    }
    @Test fun simultaneousCardsShareFlightAndCache() = runTest {
        var calls = 0
        val catalog = FidoModelCatalog({ calls++; response() }, scope = this)
        val first = async { catalog.lookup(known, "en-US") }
        val second = async { catalog.lookup(other, "en-US") }
        assertTrue(first.await() is FidoModelName.Known)
        assertTrue(second.await() is FidoModelName.Known)
        assertEquals(1, calls)
        catalog.lookup(other, "en-US")
        assertEquals(1, calls)
    }
    @Test fun staleCatalogSurvivesFailureAndUsesConditionalRequest() = runTest {
        var time = 0L
        var calls = 0
        val catalog = FidoModelCatalog({ tag ->
            calls++
            if (calls == 1) response() else { assertEquals("\"291\"", tag); response(503) }
        }, now = { time }, scope = this)
        val original = catalog.lookup(known, "en-US")
        time = 301_000
        assertEquals(original, catalog.lookup(known, "en-US"))
        assertEquals(original, catalog.lookup(known, "en-US"))
        assertEquals(2, calls)
    }
    @Test fun rateLimitWaitsForRetryAfterAndMalformedCatalogFails() = runTest {
        var time = 0L
        var calls = 0
        val catalog = FidoModelCatalog({ calls++; response(429, retry = "600") }, now = { time }, scope = this)
        assertEquals(FidoModelName.Failed, catalog.lookup(known, "en-US"))
        time = 599_999
        catalog.lookup(other, "en-US")
        assertEquals(1, calls)
        time = 600_000
        catalog.lookup(other, "en-US")
        assertEquals(2, calls)
        assertEquals(FidoModelName.Failed, FidoModelCatalog({ response(body = "{}") }, scope = this).lookup(known, "en-US"))
    }
    @Test fun zeroAaguidIsMissingWithoutLoadingCatalog() = runTest {
        val catalog = FidoModelCatalog({ error("Zero AAGUID must not request the catalog") }, scope = this)
        val zero = "00000000-0000-0000-0000-000000000000"
        assertEquals(FidoModelName.Missing, catalog.cached(zero, "en-US"))
        assertEquals(FidoModelName.Missing, catalog.lookup(zero, "en-US"))
    }

    @Test fun freshnessUsesGreaterOfAgeAndApparentDateAge() = runTest {
        for (age in listOf(10, 200)) {
            var time = 200_000L
            var calls = 0
            val catalog = FidoModelCatalog({
                calls++
                response().newBuilder().header("Age", age.toString())
                    .header("Date", "Thu, 01 Jan 1970 00:01:40 GMT").build()
            }, now = { time }, scope = this)
            catalog.lookup(known, "en-US")
            val remaining = 300_000L - maxOf(age * 1000L, 100_000L)
            time += remaining - 1
            catalog.lookup(known, "en-US")
            assertEquals(1, calls)
            time++
            catalog.lookup(known, "en-US")
            assertEquals(2, calls)
        }
    }

    @Test fun retryAfterLongerThanOneDayIsHonoredForSecondsAndHttpDate() = runTest {
        for (retry in listOf("172800", "Sat, 03 Jan 1970 00:00:00 GMT")) {
            var time = 0L
            var calls = 0
            val catalog = FidoModelCatalog({ calls++; response(429, retry = retry) }, now = { time }, scope = this)
            catalog.lookup(known, "en-US")
            time = 86_400_000
            catalog.lookup(other, "en-US")
            assertEquals(1, calls)
            time = 172_799_999
            catalog.lookup(other, "en-US")
            assertEquals(1, calls)
            time++
            catalog.lookup(other, "en-US")
            assertEquals(2, calls)
        }
    }

}
