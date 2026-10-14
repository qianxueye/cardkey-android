package cc.cardkey.enrollment

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cc.cardkey.protocol.*
import cc.cardkey.transport.*
import cc.cardkey.ui.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
// The desktop SunJSSE fixture requires the host EC parameter implementation.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EnrollmentViewModelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun reader(): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "enrollment-device")
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        return UsbReaderDescriptor(device, 1, CardWire.APDU)
    }
    private fun model(fixture: EnrollmentFixtures, address: String = "https://localhost") = ManagerViewModel(app).apply {
        selectReader(reader())
        enrollmentSettings(address, fixture.csr.cn)
        enrollmentRoot(enrollment.rootRequest(), fixture.root)
    }
    private fun run(model: ManagerViewModel, input: OperationInput, channel: CardChannel) {
        val state = ReflectionHelpers.getField<MutableStateFlow<ManagerState>>(model, "mutable")
        state.value = model.state.value.copy(busy = true, operation = input.operation, result = null)
        val method = ManagerViewModel::class.java.getDeclaredMethod("run", OperationInput::class.java, Function0::class.java).apply { isAccessible = true }
        method.invoke(model, input, { channel })
    }
    private fun await(model: ManagerViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (model.state.value.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(model.state.value.busy)
    }
    @Test fun documentSelectorAbaRejectsPendingConfirmationAndClearsItsSecrets() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val selection = EnrollmentSelection(EnrollmentPurpose.DOCUMENT, 0x9c)
        model.enrollmentSelection(selection)
        val pending = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "123456".toCharArray(),
            text = fixture.csr.cn, enrollmentSelection = selection, enrollmentRevision = model.enrollment.state.value.revision)
        model.enrollmentSelection(selection.copy(slot = 0x9a))
        model.enrollmentSelection(selection)
        model.submitEnrollment(pending)
        assertTrue(pending.pin.all { it == '\u0000' })
        assertFalse(model.state.value.busy)
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
        assertNull(model.enrollment.state.value.csr)
    }
    @Test fun issuanceAndExportCallbacksCannotReturnAfterSelectionAba() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val selected = EnrollmentSelection(EnrollmentPurpose.DOCUMENT)
        model.enrollmentSelection(selected)
        val csr = fixture.csr(selection = selected)
        model.enrollment.settleCsr(model.enrollment.startCsr(), csr)
        val revision = model.enrollment.state.value.revision
        val report = ManagerReport(emptyList(), csr.pem().toByteArray(), "piv-9c.csr", enrollmentCsr = csr)
        model.cancel()
        assertTrue(model.currentEnrollmentExport(report))
        model.enrollmentSelection(EnrollmentSelection())
        model.enrollmentSelection(selected)
        val replacement = fixture.csr(selection = selected)
        model.enrollment.settleCsr(model.enrollment.startCsr(), replacement)
        assertFalse(model.currentEnrollmentExport(report))
        val token = fixture.token(replacement).toCharArray()
        model.issueEnrollment(token, revision)
        assertTrue(token.all { it == '\u0000' })
        assertFalse(model.enrollment.state.value.attempted)
        assertEquals(EnrollmentStage.CSR, model.enrollment.state.value.stage)
        assertFalse(model.state.value.busy)
    }
    @Test fun documentImportCannotOpenTlsRootPickerOrActualTlsConnection() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val selection = EnrollmentSelection(EnrollmentPurpose.DOCUMENT, 0x9a)
        model.enrollmentSelection(selection)
        val csr = fixture.csr(selection = selection)
        model.enrollment.settleCsr(model.enrollment.startCsr(), csr)
        val (owner, _) = model.enrollment.startIssuance(fixture.token(csr))
        val eku = org.bouncycastle.asn1.x509.KeyPurposeId.getInstance(org.bouncycastle.asn1.ASN1ObjectIdentifier(selection.purpose.eku))
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(purposes = arrayOf(eku)), fixture.intermediate), fixture.root, csr)
        model.enrollment.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
        model.enrollment.imported(owner)
        model.requestTlsRoot()
        assertNull(ReflectionHelpers.getField<Any?>(model, "pendingTlsRoot"))
        var connected = false
        val pin = "123456".toCharArray()
        val method = ManagerViewModel::class.java.getDeclaredMethod("startTls", CharArray::class.java, Function0::class.java).apply { isAccessible = true }
        method.invoke(model, pin, { connected = true; EnrollmentCard() })
        assertFalse(connected)
        assertTrue(pin.all { it == '\u0000' })
        assertFalse(model.state.value.busy)
    }
    @Test fun actualCardRunClosesGateAndSecretsBeforeHttpAndPauseKeepsCsr() {
        val fixture = EnrollmentFixtures()
        val card = EnrollmentCard()
        var secret = CharArray(0)
        EnrollmentHttpsServer(fixture) {
            assertTrue(card.closed)
            assertTrue(secret.all { it == '\u0000' })
            CardOperationGate.tryAcquire()!!.close()
            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
        }.use { server ->
            val model = model(fixture, server.address)
            val owner = model.enrollment.startCsr()
            val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "123456".toCharArray(), text = fixture.csr.cn)
            run(model, input, card); await(model)
            val csr = model.enrollment.state.value.csr!!
            assertEquals(owner, model.enrollment.generation)
            assertTrue(input.pin.all { it == '\u0000' })
            model.cancel()
            assertSame(csr, model.enrollment.state.value.csr)
            secret = fixture.token(csr, server.address).toCharArray()
            model.issueEnrollment(secret); await(model)
            assertEquals("result=${model.state.value.result}; requests=${server.requests.get()}; server=${server.lastFailure}",
                EnrollmentStage.REJECTED, model.enrollment.state.value.stage)
            assertFalse(model.state.value.requiresInspection)
            assertEquals(1, server.requests.get())
        }
    }
    @Test fun queuedCancellationDoesNotDispatchOrSpendTheCsrAttempt() {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val fixture = EnrollmentFixtures()
        EnrollmentHttpsServer(fixture) { error("Queued cancellation must not reach HTTP") }.use { server ->
            val model = model(fixture, server.address)
            model.enrollment.settleCsr(model.enrollment.startCsr(), fixture.csr)
            repeat(2) {
                val secret = fixture.token(address = server.address).toCharArray()
                model.issueEnrollment(secret)
                assertEquals(EnrollmentStage.ISSUING, model.enrollment.state.value.stage)
                model.cancel()
                scheduler.runCurrent()
                assertTrue(secret.all { it == '\u0000' })
                assertEquals(EnrollmentStage.CSR, model.enrollment.state.value.stage)
                assertFalse(model.enrollment.state.value.attempted)
                assertNull(model.enrollment.state.value.receipt)
                assertTrue(model.enrollment.state.value.unknownReceipts.isEmpty())
                assertFalse(model.state.value.busy)
            }
            assertEquals(0, server.requests.get())
        }
    }
    @Test fun cancelledNetworkCannotReplaceNewFormAndNeverCreatesCardFence() {
        val fixture = EnrollmentFixtures()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        EnrollmentHttpsServer(fixture) {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
        }.use { server ->
            val model = model(fixture, server.address)
            model.enrollment.settleCsr(model.enrollment.startCsr(), fixture.csr)
            model.issueEnrollment(fixture.token(address = server.address).toCharArray())
            try {
                val arrived = entered.await(5, TimeUnit.SECONDS)
                assertTrue("stage=${model.enrollment.state.value.stage}; result=${model.state.value.result}; requests=${server.requests.get()}; server=${server.lastFailure}", arrived)
                model.cancel()
                assertEquals(EnrollmentStage.UNKNOWN, model.enrollment.state.value.stage)
                assertFalse(model.state.value.requiresInspection)
                model.enrollmentSettings("https://different", "new")
            } finally { release.countDown() }
            await(model)
            assertEquals("new", model.enrollment.state.value.cn)
            assertEquals(EnrollmentStage.EMPTY, model.enrollment.state.value.stage)
            assertEquals(EnrollmentStage.UNKNOWN, model.enrollment.state.value.receipt!!.stage)
        }
    }
    @Test fun readerAbaDetachAndExplicitAbandonmentRetirePublicEligibility() {
        val fixture = EnrollmentFixtures()
        for (change in listOf("aba", "detach", "abandon")) {
            val model = model(fixture)
            model.enrollment.settleCsr(model.enrollment.startCsr(), fixture.csr)
            when (change) {
                "aba" -> { val selected = model.state.value.selectedReader; model.selectReader(null); model.selectReader(selected) }
                "detach" -> { shadowOf(app.getSystemService(UsbManager::class.java)).removeUsbDevice(model.state.value.selectedReader!!.device); model.refreshReaders() }
                else -> model.abandonEnrollment()
            }
            assertNull(model.enrollment.state.value.csr)
        }
    }
    @Test fun latePinFailureAfterCancellationKeepsCurrentOwnedDiagnostics() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val card = EnrollmentCard().apply { pinStatus = 0x63c2 }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val channel = object : CardChannel by card {
            override fun transmit(command: ByteArray): ByteArray {
                if (command[1] == 0x20.toByte()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return card.transmit(command)
            }
            override fun cancel() { release.countDown() }
        }
        model.enrollment.startCsr()
        run(model, OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "999999".toCharArray(), text = fixture.csr.cn), channel)
        assertTrue(entered.await(5, TimeUnit.SECONDS)); model.cancel(); release.countDown(); await(model)
        val result = model.state.value.result as OperationResult.Failed
        assertEquals(FailureCode.PIN_INVALID, result.code); assertEquals(0x63c2, result.status)
        assertNull(model.enrollment.state.value.csr)
    }
    @Test fun enrollmentImportRetainsUnknownAndAcceptedReadbackRecoveryFences() {
        val fixture = EnrollmentFixtures()
        for (lost in listOf(true, false)) {
            val model = model(fixture)
            val card = EnrollmentCard().apply { lostWrite = lost; corruptReadback = !lost }
            val csr = fixture.csr(key = card.keyPair)
            val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(key = card.keyPair), fixture.intermediate), fixture.root, csr)
            model.enrollment.settleCsr(model.enrollment.startCsr(), csr)
            val (owner, _) = model.enrollment.startIssuance(fixture.token(csr))
            model.enrollment.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
            val input = OperationInput(ManagerOperation.PIV_ENROLL_IMPORT, adminKey = card.managementKey.copyOf(),
                enrollmentImport = model.enrollment.importRequest())
            run(model, input, card); await(model)
            val result = model.state.value.result as OperationResult.Failed
            assertEquals(if (lost) Effect.UNKNOWN else Effect.APPLIED, result.effect)
            assertEquals(!lost, result.writeAccepted)
            assertTrue(model.state.value.requiresInspection)
            assertNotEquals(EnrollmentStage.IMPORTED, model.enrollment.state.value.stage)
            assertTrue(input.adminKey.all { it == 0.toByte() })
            CardOperationGate.tryAcquire()!!.close()
        }
    }

    @Test fun queuedCancellationCannotTouchAnAlreadyRetiredChannel() {
        val model = ManagerViewModel(app)
        var touches = 0
        val delegate = object : CardChannel by EnrollmentCard() {
            override fun setUserPresenceListener(listener: ((Boolean) -> Unit)?) { touches++ }
            override fun cancel() { touches++ }
        }
        val channel = CancellableCardChannel(delegate) { false }
        val current = ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicReference<CancellableCardChannel?>>(model, "currentChannel")
        val lock = ReflectionHelpers.getField<Any>(model, "presenceLock")
        val jobs: List<Job>
        synchronized(lock) {
            current.set(channel)
            model.cancel()
            jobs = model.viewModelScope.coroutineContext[Job]!!.children.toList()
            assertTrue(jobs.isNotEmpty())
            current.set(null)
        }
        runBlocking { jobs.forEach { it.join() } }
        assertEquals(0, touches)
        CardOperationGate.tryAcquire()!!.close()
    }

    @Test fun lastPinAttemptUsesExistingWarningAndRejectsBeforeVerification() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val card = EnrollmentCard().apply { pinRemaining = 1; pukRemaining = 0 }
        model.enrollment.startCsr()
        val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "123456".toCharArray(), text = fixture.csr.cn)
        run(model, input, card)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.state.value.lastRetry == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("PIN" to "PUK", model.state.value.lastRetry)
        model.approveLastRetry(false); await(model)
        assertTrue(card.commands.none { it.ins == 0x20 })
        assertTrue(input.pin.all { it == '\u0000' })
    }
}
