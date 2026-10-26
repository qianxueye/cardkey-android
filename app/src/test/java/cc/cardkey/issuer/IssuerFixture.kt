package cc.cardkey.issuer

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList

internal class IssuerFixture : AutoCloseable {
    val personID = "11111111-1111-4111-8111-111111111111"
    val cardID = "22222222-2222-4222-8222-222222222222"
    val revisionID = "33333333-3333-4333-8333-333333333333"
    val oldID = "44444444-4444-4444-8444-444444444444"
    val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    val png = ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(20, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(35, 57, 91))
        android.graphics.Canvas(bitmap).drawRect(2f, 4f, 18f, 18f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(220, 230, 240) })
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
    }
    private val executor = Executors.newCachedThreadPool()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val origin get() = "http://127.0.0.1:${server.localPort}"
    var personName = "Synthetic Person"
    var failure = 0
    var failurePath: String? = null
    var peopleFirstPageHasPerson = false
    var mediaFailure = 0
    var currentMissing = false
    var historyMissing = false
    var mediaType = "image/png"
    var mediaBytes: ByteArray? = null
    var redirect = "https://other.invalid/"
    var delayPath: String? = null
    var delayMillis = 0L
    var delayToken: String? = null
    var rejectedTokens: Set<String> = emptySet()
    var peopleEmpty = false
    var cardsEmpty = false
    var historyEmpty = false
    var historyFirstPageEmpty = false
    var peoplePages = false
    var cardPages = false
    var historyPages = false
    var enteredDelay = java.util.concurrent.CountDownLatch(1)
    init {
        executor.execute { while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            executor.execute { serve(socket) }
        } }
    }
    private fun serve(socket: Socket) {
        socket.use {
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader()
            val request = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) { val line = input.readLine() ?: return; if (line.isEmpty()) break; headers[line.substringBefore(":").lowercase()] = line.substringAfter(":").trim() }
            val exchange = Exchange(socket, URI(request.split(" ")[1]), headers)
            try {
                val path = exchange.requestURI.path
                requests.add(exchange.requestURI.toString() to exchange.requestHeaders["authorization"])
                if (path == delayPath && (delayToken == null || exchange.requestHeaders["authorization"] == "Bearer $delayToken")) { enteredDelay.countDown(); Thread.sleep(delayMillis) }
                if (exchange.requestHeaders["authorization"]?.removePrefix("Bearer ") in rejectedTokens) respond(exchange, 401, "application/json", "{}".toByteArray())
                else if (failure != 0 && (failurePath == null || failurePath == path)) { exchange.responseHeaders["Location"] = redirect; respond(exchange, failure, "application/json", "{}".toByteArray()) }
                else when {
                    path == "/v1/me" -> json(exchange, """{"operator":{"id":"test-operator","display_name":"Test Operator","administrator":false},"capabilities":["people:read","cards:read"],"ca_id":"test-ca","ca_url":"https://ca.invalid"}""")
                    path == "/v1/people" -> json(exchange, page(if (peopleEmpty || peoplePages && !peopleFirstPageHasPerson && !exchange.requestURI.query.contains("cursor=second")) "" else person(), if (peoplePages && !exchange.requestURI.query.contains("cursor=second")) "second" else ""))
                    path == "/v1/cards" -> json(exchange, page(if (cardsEmpty || cardPages && !exchange.requestURI.query.contains("cursor=second")) "" else card(), if (cardPages && !exchange.requestURI.query.contains("cursor=second")) "second" else ""))
                    path.endsWith("/badge-current") -> if (currentMissing) respond(exchange, 404, "application/json", "{}".toByteArray()) else json(exchange, revision())
                    path.endsWith("/badge-revisions") -> if (historyMissing) respond(exchange, 404, "application/json", "{}".toByteArray()) else json(exchange, page(if (historyEmpty || historyFirstPageEmpty && !exchange.requestURI.query.contains("cursor=older")) "" else if (exchange.requestURI.query.contains("cursor=older")) revision(oldID) else revision(), if (historyPages && !exchange.requestURI.query.contains("cursor=older")) "older" else ""))
                    path.endsWith("/front") || path.endsWith("/back") -> respond(exchange, if (mediaFailure == 0) 200 else mediaFailure, mediaType, mediaBytes ?: png)
                    else -> respond(exchange, 404, "application/json", "{}".toByteArray())
                }
            } catch (_: Exception) { exchange.close() }
        }
    }
    fun person() = """{"id":"$personID","employee_reference":"EMP-001","display_name":"$personName","badge_number":"B-01","department":"Example","job_title":"Operator","employment_status":"inactive","version":3,"created_at":"2026-10-26T00:00:00Z","updated_at":"2026-10-26T00:00:00Z"}"""
    fun card() = """{"id":"$cardID","person_id":"$personID","serial":"123456","status":"lost","version":2}"""
    fun revision(id: String = revisionID): String {
        val image = """{"media_type":"image/png","byte_length":${png.size},"width":20,"height":32,"accessibility_label":"${if (id == oldID) "Synthetic historical portrait card" else "Synthetic portrait card"}"}"""
        return """{"id":"$id","person_id":"$personID","card_id":"$cardID","source_output_id":"$oldID","source_created_at":"2026-10-25T00:00:00Z","person_version":1,"card_version":1,"person_snapshot":{"employee_reference":"EMP-001","display_name":"Historical Person","badge_number":"B-01","department":"Previous department","job_title":"Previous role"},"created_at":"2026-10-26T00:00:00Z","actor":"publisher","front":$image,"back":$image}"""
    }
    private fun page(items: String, cursor: String) = """{"items":[$items],"next_cursor":"$cursor"}"""
    private fun json(exchange: Exchange, body: String) { respond(exchange, 200, "application/json", body.toByteArray()) }
    private fun respond(exchange: Exchange, code: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders["Content-Type"] = type
        val header = "HTTP/1.1 $code Fixture\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" + exchange.responseHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
        exchange.socket.getOutputStream().use { it.write(header.toByteArray()); it.write(bytes); it.flush() }
    }
    private class Exchange(val socket: Socket, val requestURI: URI, val requestHeaders: Map<String, String>) {
        val responseHeaders = mutableMapOf<String, String>()
        fun close() = socket.close()
    }
    override fun close() {
        server.close(); executor.shutdownNow()
        check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
    }
}
