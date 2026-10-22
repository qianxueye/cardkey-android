package cc.cardkey.tls

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import cc.cardkey.enrollment.*
import cc.cardkey.protocol.*
import cc.cardkey.transport.*
import cc.cardkey.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
// The desktop SunJSSE fixture requires the host EC parameter implementation.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [TlsDnsShadow::class], manifest = Config.NONE)
class TlsViewModelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { TlsDnsShadow.reset(); Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(f: TlsFixture): ManagerViewModel = ManagerViewModel(app).apply {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "tls-device")
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        selectReader(UsbReaderDescriptor(device, 1, CardWire.APDU))
        enrollmentSettings("https://localhost", f.csr.cn)
        enrollmentRoot(enrollment.rootRequest(), f.issued.root)
        enrollment.settleCsr(enrollment.startCsr(), f.csr)
        val (owner, _) = enrollment.startIssuance(f.issued.token(f.csr))
        enrollment.settleIssuance(owner, f.bundle, EnrollmentStage.VERIFIED)
        enrollment.imported(owner)
    }
    private fun root(model: ManagerViewModel, f: TlsFixture) {
        model.requestTlsRoot()
        val uri = Uri.parse("content://tls-test/root-${System.nanoTime()}")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(f.issued.root))
        model.receiveTlsRoot(uri)
        await { model.tls.value.serverRootSubject != null }
    }
    private fun start(model: ManagerViewModel, channel: CardChannel): CharArray {
        val pin = "123456".toCharArray()
        val method = ManagerViewModel::class.java.getDeclaredMethod("startTls", CharArray::class.java, Function0::class.java).apply { isAccessible = true }
        method.invoke(model, pin, { channel })
        return pin
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }

    @Test fun actualManagerRunUsesImportedSnapshotAndPreservesIssuanceReceipt() {
        val f = TlsFixture()
        val model = model(f)
        val receipt = model.enrollment.state.value.receipt
        TlsServer(f).use { server ->
            model.tlsSettings(server.address); root(model, f)
            val pin = start(model, f.card)
            assertTrue(pin.all { it == '\u0000' })
            await { !model.state.value.busy }
            assertEquals(server.failure?.toString(), PivTlsOutcome.QUALIFIED, model.tls.value.result?.outcome)
            assertFalse(model.tls.value.running)
            assertTrue(model.state.value.result is OperationResult.Completed)
            assertSame(receipt, model.enrollment.state.value.receipt)
            assertEquals(EnrollmentStage.IMPORTED, model.enrollment.state.value.stage)
            assertEquals(1, f.card.commands.count { it.ins == 0x87 })
        }
    }

    @Test fun lastRetryConfirmationOwnsNoCardOrSocketAndChangedFactsAbortPin() {
        val f = TlsFixture()
        f.card.pinRemaining = 1; f.card.pukRemaining = 0
        val model = model(f)
        TlsServer(f).use { server ->
            model.tlsSettings(server.address); root(model, f); start(model, f.card)
            await { model.state.value.lastRetry != null }
            assertEquals("PIN" to "PUK", model.state.value.lastRetry)
            assertTrue(f.card.closed)
            CardOperationGate.tryAcquire()!!.close()
            assertEquals(0, server.accepted.get())
            assertEquals(0, f.card.commands.count { it.ins == 0x20 })
            f.card.pinRemaining = 2
            model.approveLastRetry(true)
            await { !model.state.value.busy }
            assertEquals(FailureCode.RECOVERY_REQUIRED, (model.state.value.result as OperationResult.Failed).code)
            assertEquals(0, f.card.commands.count { it.ins == 0x20 })
        }
    }

    @Test fun cancelledPinResponseRetainsUnknownAndBlocksManagerUntilCleanup() {
        val f = TlsFixture()
        val model = model(f)
        ReflectionHelpers.setField(model, "operationProtectedIdentity", "previous-guid:issuer-fingerprint")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val channel = object : CardChannel by f.card {
            override fun transmit(command: ByteArray): ByteArray {
                if (command[1].toInt() and 255 == 0x20) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    throw TransportException(Dispatch.RESPONSE_UNAVAILABLE)
                }
                return f.card.transmit(command)
            }
            override fun cancel() { }
        }
        TlsServer(f).use { server ->
            model.tlsSettings(server.address); root(model, f); start(model, channel)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                model.cancel()
                assertTrue(model.state.value.busy)
                val input = OperationInput(ManagerOperation.PIV_SIGN, pin = "123456".toCharArray())
                model.submit(input)
                assertTrue(input.pin.all { it == '\u0000' })
            } finally { release.countDown() }
            await { !model.state.value.busy }
            val failure = model.state.value.result as OperationResult.Failed
            assertEquals(Effect.UNKNOWN, failure.effect)
            assertEquals(Dispatch.RESPONSE_UNAVAILABLE, failure.dispatch)
            assertTrue(model.state.value.requiresInspection)
            assertNull(ReflectionHelpers.getField<String?>(model, "recoveryProtectedIdentity"))
            val finish = ManagerViewModel::class.java.getDeclaredMethod("finish", OperationResult::class.java).apply { isAccessible = true }
            val mutable = ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<ManagerState>>(model, "mutable")
            val route = model.state.value.selectedReader!!.routeKey
            mutable.value = mutable.value.copy(operation = ManagerOperation.PIV_INFO)
            ReflectionHelpers.setField(model, "operationRoute", "previous-protected-reader")
            finish.invoke(model, OperationResult.Completed(ManagerReport(emptyList(),
                protectedPivIdentity = "previous-guid:issuer-fingerprint"), Effect.NONE))
            assertTrue(model.state.value.requiresInspection)
            ReflectionHelpers.setField(model, "operationRoute", route)
            finish.invoke(model, OperationResult.Completed(ManagerReport(emptyList()), Effect.NONE))
            assertFalse(model.state.value.requiresInspection)
            assertEquals(PivTlsOutcome.CANCELLED, model.tls.value.result?.outcome)
            assertEquals(0, f.card.commands.count { it.ins == 0x87 })
        }
    }

    @Test fun cancellationDuringPublicPreflightKeepsNoPinEffectAndCancelledReceipt() {
        val f = TlsFixture()
        val model = model(f)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val channel = object : CardChannel by f.card {
            override fun transmit(command: ByteArray): ByteArray {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return f.card.transmit(command)
            }
            override fun cancel() { }
        }
        TlsServer(f).use { server ->
            model.tlsSettings(server.address); root(model, f); start(model, channel)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                model.cancel()
                assertTrue(model.state.value.busy)
            } finally { release.countDown() }
            await { !model.state.value.busy }
            assertEquals(PivTlsOutcome.CANCELLED, model.tls.value.result?.outcome)
            assertEquals(Effect.NONE, (model.state.value.result as OperationResult.Failed).effect)
            assertEquals(0, server.accepted.get())
            assertEquals(0, f.card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
        }
    }

    @Test fun dnsWithoutCallbackCannotKeepManagerBusyAfterCancelOrReviveFromLateAnswer() {
        TlsDnsShadow.defer = true
        val f = TlsFixture()
        val model = model(f)
        TlsServer(f).use { server ->
            model.tlsSettings(server.address); root(model, f)
            val pin = start(model, f.card)
            await { TlsDnsShadow.answer != null }
            assertTrue(pin.all { it == '\u0000' })
            assertTrue(model.state.value.busy)
            assertTrue(f.card.closed)
            CardOperationGate.tryAcquire()!!.close()
            val cancellingThread = Thread.currentThread()
            val attempt = ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicReference<PivTlsAttempt>>(model, "tlsAttempt").get()
            val connection = ReflectionHelpers.getField<PivTlsConnection>(attempt, "connection")
            val dns = ReflectionHelpers.getField<PivTlsDns>(connection, "dns")
            // Let the real TLS completion publish before the initiating cancellation resumes.
            ReflectionHelpers.setField(connection, "dns", object : PivTlsDns by dns {
                override fun close() {
                    dns.close()
                    if (Thread.currentThread() === cancellingThread) await { !model.state.value.busy }
                }
            })
            model.cancel()
            await { !model.state.value.busy }
            assertTrue(TlsDnsShadow.cancellation!!.isCanceled)
            assertEquals(PivTlsOutcome.CANCELLED, model.tls.value.result?.outcome)
            assertEquals(Effect.NONE, (model.state.value.result as OperationResult.Failed).effect)
            assertFalse(model.state.value.requiresInspection)
            assertFalse(model.tls.value.running)
            TlsDnsShadow.answer!!.invoke(listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), 0)
            assertEquals(0, server.accepted.get())
            assertEquals(0, f.card.commands.count { it.ins == 0x20 || it.ins == 0x87 })
            assertEquals(PivTlsOutcome.CANCELLED, model.tls.value.result?.outcome)
        }
    }

    @Test fun pickerPauseSurvivesButAddressAbaAndRouteAbaRejectPendingRoot() {
        for (change in listOf("pause", "address", "route")) {
            val f = TlsFixture()
            val model = model(f)
            model.tlsSettings("https://localhost/qualify")
            model.requestTlsRoot()
            when (change) {
                "pause" -> model.cancel()
                "address" -> { model.tlsSettings("https://different.invalid"); model.tlsSettings("https://localhost/qualify") }
                "route" -> { val reader = model.state.value.selectedReader; model.selectReader(null); model.selectReader(reader) }
            }
            val uri = Uri.parse("content://tls-test/$change")
            val read = CountDownLatch(1)
            shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(f.issued.root) {
                override fun close() { super.close(); read.countDown() }
            })
            model.receiveTlsRoot(uri)
            if (change == "pause") {
                await { model.tls.value.serverRootSubject != null }
                assertEquals(EnrollmentStage.IMPORTED, model.enrollment.state.value.stage)
            } else assertNull(model.tls.value.serverRootSubject)
        }
    }

    @Test fun staleConfirmationCannotSignAfterSettingsAba() {
        val f = TlsFixture()
        val model = model(f)
        model.tlsSettings("https://localhost/qualify"); root(model, f)
        val input = TlsQualificationInput(model.tls.value.revision, f.bundle, model.tls.value.address, "123456".toCharArray())
        model.tlsSettings("https://different.invalid"); model.tlsSettings(input.address)
        model.submitTls(input)
        assertTrue(input.pin.all { it == '\u0000' })
        assertFalse(model.state.value.busy)
        assertNull(model.tls.value.result)
    }
}
