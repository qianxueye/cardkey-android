package cc.cardkey.tls

import android.net.DnsResolver
import android.os.CancellationSignal
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal interface PivTlsDns : Closeable {
    fun resolve(host: String): InetAddress
}

internal class AndroidPivTlsDns : PivTlsDns {
    private val cancellation = CancellationSignal()
    private val closed = AtomicBoolean()
    private val used = AtomicBoolean()
    private val answer = CompletableFuture<InetAddress>()

    override fun resolve(host: String): InetAddress {
        check(used.compareAndSet(false, true))
        if (closed.get()) throw IOException("DNS cancelled")
        DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_NO_RETRY, Executor { it.run() }, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(addresses: List<InetAddress>, rcode: Int) {
                    val first = addresses.firstOrNull()
                    if (closed.get()) return
                    if (rcode == 0 && first != null) answer.complete(first)
                    else answer.completeExceptionally(IOException("DNS answer rejected"))
                }
                override fun onError(error: DnsResolver.DnsException) {
                    if (!closed.get()) answer.completeExceptionally(IOException("DNS resolution failed", error))
                }
            })
        val resolved = try { answer.get() }
        catch (error: ExecutionException) { throw IOException("DNS resolution failed", error.cause) }
        if (closed.get()) throw IOException("DNS cancelled")
        return resolved
    }

    override fun close() {
        closed.set(true)
        // Wake the owner even when Android suppresses callbacks after cancellation.
        answer.completeExceptionally(IOException("DNS cancelled"))
        cancellation.cancel()
    }
}
