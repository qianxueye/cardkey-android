package cc.cardkey.tls

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import java.net.InetAddress
import java.util.concurrent.Executor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow

@Implements(DnsResolver::class)
class TlsDnsShadow {
    @Implementation
    fun query(network: Network?, domain: String, flags: Int, executor: Executor,
              signal: CancellationSignal?, callback: DnsResolver.Callback<in List<InetAddress>>) {
        check(network == null)
        check(flags == DnsResolver.FLAG_NO_RETRY)
        queries++
        host = domain
        cancellation = requireNotNull(signal)
        error = { failure -> executor.execute { callback.onError(failure) } }
        answer = { addresses, rcode -> executor.execute { callback.onAnswer(addresses, rcode) } }
        if (!defer) answer!!.invoke(listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), 0)
    }
    companion object {
        @JvmStatic @Implementation fun getInstance(): DnsResolver = Shadow.newInstanceOf(DnsResolver::class.java)
        @Volatile var defer = false
        @Volatile var queries = 0
        @Volatile var host: String? = null
        @Volatile var cancellation: CancellationSignal? = null
        @Volatile var answer: ((List<InetAddress>, Int) -> Unit)? = null
        @Volatile var error: ((DnsResolver.DnsException) -> Unit)? = null
        @JvmStatic @Resetter fun reset() {
            defer = false; queries = 0; host = null; cancellation = null; answer = null; error = null
        }
    }
}
