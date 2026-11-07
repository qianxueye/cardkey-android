package cc.cardkey.enrollment

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cc.cardkey.protocol.*
import cc.cardkey.transport.*
import cc.cardkey.ui.*
import cc.cardkey.issuer.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val models = mutableListOf<ManagerViewModel>()
    private lateinit var mainDispatcher: TestDispatcher
    private val issuerFixtures = mutableListOf<IssuerFixture>()
    @Before fun setup() {
        mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }
    @After fun teardown() {
        val jobs = models.map { model -> model.cancel(); model.viewModelScope.coroutineContext[Job]!! }
        jobs.forEach { it.cancel() }
        // Cancellation clears busy state before IO continuations finish using Main.
        runBlocking {
            withTimeout(10000) {
                while (jobs.any { !it.isCompleted }) {
                    mainDispatcher.scheduler.runCurrent()
                    delay(1)
                }
            }
        }
        issuerFixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }
    private fun reader(): UsbReaderDescriptor {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "enrollment-device")
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        return UsbReaderDescriptor(device, 1, CardWire.APDU)
    }
    private fun model(fixture: EnrollmentFixtures, address: String = "https://localhost") = app.let {
        check(it.getSharedPreferences("enrollment_settings", android.content.Context.MODE_PRIVATE).edit().clear().commit())
        ManagerViewModel(it)
    }.also(models::add).apply {
        selectReader(reader())
        enrollmentSettings(address)
        enrollment.choosePerson(enrollmentTarget("https://issuer.example.test", fixture.csr.identity))
        enrollmentRoot(enrollment.rootRequest(), fixture.root)
    }
    private fun attachIssuer(model: ManagerViewModel, fixture: EnrollmentFixtures, caAddress: String,
                             errorRoute: String? = null, errorCode: String = "", status: Int = 409): IssuerFixture {
        val issuer = IssuerFixture().also(issuerFixtures::add)
        val csr = requireNotNull(model.enrollment.state.value.csr)
        var row: JsonObject? = null
        issuer.enrollmentResponse = { method, path, body ->
            when {
                errorRoute != null && (path.endsWith(errorRoute) ||
                    errorRoute == "/intent" && method == "PUT" && !path.endsWith("/result")) ->
                    status to """{"error":{"code":"$errorCode"}}"""
                path.endsWith("/token") -> 200 to JsonObject().apply {
                    addProperty("token", fixture.token(csr, caAddress)); addProperty("jti", "synthetic-jti")
                    addProperty("exp", fixture.now / 1000 + 300); addProperty("provisioner_role", when (csr.selection.purpose) {
                        EnrollmentPurpose.TLS -> "piv_auth"
                        EnrollmentPurpose.DOCUMENT -> "signing"
                        EnrollmentPurpose.KEY_MANAGEMENT -> "key_management"
                    })
                }.toString()
                path.endsWith("/result") -> {
                    val result = JsonParser.parseString(body).asJsonObject
                    val updated = requireNotNull(row).deepCopy().apply {
                        add("outcome", result.get("outcome"))
                        result.get("certificate_id")?.let { add("certificate_id", it) }
                    }
                    row = updated
                    200 to updated.toString()
                }
                method == "PUT" -> {
                    row = JsonParser.parseString(body).asJsonObject.apply {
                        addProperty("id", path.substringAfterLast('/')); addProperty("outcome", "pending")
                        addProperty("certificate_id", "")
                        add("certificate_identity", JsonObject().apply {
                            addProperty("employee_reference", csr.identity.employeeReference)
                            addProperty("display_name", csr.identity.displayName)
                            addProperty("upn", csr.identity.upn); addProperty("email", csr.identity.email)
                        })
                    }
                    200 to row.toString()
                }
                else -> 200 to requireNotNull(row).toString()
            }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = issuer.origin.toHttpUrl().newBuilder().encodedPath(request.url.encodedPath)
                .encodedQuery(request.url.encodedQuery).build()
            chain.proceed(request.newBuilder().url(url).build())
        }.build()
        val client = IssuerClient(requireNotNull(model.enrollment.state.value.target).issuerOrigin, "synthetic", http)
        runBlocking { client.me() }
        model.enrollmentIssuerClient = { client }
        return issuer
    }

    private fun issuerErrorKeepsUnknownReceipt(code: String, english: String, chinese: String,
                                              route: String = "/token", status: Int = 409,
                                              selection: EnrollmentSelection = EnrollmentSelection()) {
        val fixture = EnrollmentFixtures()
        EnrollmentHttpsServer(fixture) { "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n" }.use { server ->
            val model = model(fixture, server.address)
            model.enrollmentSelection(selection)
            val csr = if (selection.purpose == EnrollmentPurpose.KEY_MANAGEMENT) fixture.csr(
                key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
                selection = selection) else fixture.csr
            model.enrollment.settleCsr(model.enrollment.startCsr(), csr)
            val issuer = attachIssuer(model, fixture, server.address, route, code, status)
            model.issueEnrollment(); await(model)
            val state = model.enrollment.state.value
            assertEquals("$code stage", EnrollmentStage.UNKNOWN, state.stage)
            assertTrue("$code must block another authorization", state.attempted)
            assertNull(state.bundle)
            val receipt = requireNotNull(state.receipt)
            assertEquals(EnrollmentStage.UNKNOWN, receipt.stage)
            assertEquals(route == "/result", receipt.reportPending)
            assertEquals(receipt.issuanceId, state.unknownReceipts.single().issuanceId)
            val restored = EnrollmentWorkflow(EnrollmentSettings(app.getSharedPreferences(
                "enrollment_settings", android.content.Context.MODE_PRIVATE)))
            assertEquals(EnrollmentStage.UNKNOWN, restored.state.value.stage)
            assertEquals(receipt.issuanceId, restored.state.value.unknownReceipts.single().issuanceId)
            assertTrue(runCatching { restored.prepareIssuerRequest() }.isFailure)
            val message = requireNotNull(model.state.value.enrollmentError)
            for ((language, expected) in listOf("en" to english, "zh" to chinese)) {
                val configuration = android.content.res.Configuration(app.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(language))
                }
                assertEquals("$code $language", expected, app.createConfigurationContext(configuration).getString(message))
            }
            assertEquals("Only one mint attempt", if (route == "/intent") 0 else 1,
                issuer.requests.count { it.first.endsWith("/token") })
            assertEquals("Only one result report", if (route == "/result") 1 else 0,
                issuer.requests.count { it.first.endsWith("/result") })
            assertEquals("CA calls", if (route == "/result") 1 else 0, server.requests.get())
            assertFalse(model.state.value.requiresInspection)
        }
    }

    @Test fun signingAttemptUnresolvedKeepsUnknownAndExactAdministratorMessage() {
        for (route in listOf("/token", "/result")) issuerErrorKeepsUnknownReceipt("signing_attempt_unresolved",
            "Authorization for this request was used. The issuer has not recorded a signing result. Ask the issuing administrator to check this request. Do not request another authorization.",
            "本申请的授权已使用，发卡服务没有记录签发结果。请让发卡管理员检查此申请，勿再次请求授权。", route)
    }

    @Test fun signingTokenLiveDuringMintKeepsUnknownAndAlreadyRequestedMessage() = issuerErrorKeepsUnknownReceipt("signing_token_live",
        "Authorization was already requested. Ask the issuing administrator to check this request before continuing.",
        "此申请已请求过授权。请让发卡管理员核对记录后再继续。", "/token")

    @Test fun signingTokenLiveDuringResultKeepsUnknownAndExactValidAuthorizationMessage() = issuerErrorKeepsUnknownReceipt("signing_token_live",
        "The authorization is still valid. The issuer cannot record a rejected or unsubmitted result yet. Ask the issuing administrator to check this request.",
        "授权仍在有效期内，发卡服务暂不能记录拒绝或未提交的结果。请让发卡管理员检查此申请。", "/result")

    @Test fun issuanceNotPendingKeepsUnknownAndExactRequestStatusMessage() = issuerErrorKeepsUnknownReceipt(
        "issuance_not_pending", "This request has ended and cannot get another signing authorization. Ask the issuing administrator to check the request record.",
        "此申请已结束，不能再获取签发授权。请让发卡管理员查看申请记录。")

    @Test fun issuanceTerminalKeepsUnknownAndExactFinalResultMessage() = issuerErrorKeepsUnknownReceipt(
        "issuance_terminal", "This request already has a final result. Ask the issuing administrator to check the recorded result.",
        "此申请已有最终结果。请让发卡管理员检查已记录的结果。", route = "/result")

    @Test fun signingUnavailable503KeepsUnknownAndExactSigningServiceMessage() = issuerErrorKeepsUnknownReceipt(
        "signing_unavailable", "The issuer cannot provide signing authorization right now. Try again later; if it still fails, ask the issuing administrator to check the signing service.",
        "发卡服务暂时无法签发授权。请稍后重试；仍失败时请让发卡管理员检查签发服务。", status = 503)

    @Test fun keyManagementAlgorithmMismatch422KeepsUnknownAndExact9dRsa2048Message() {
        for (route in listOf("/intent", "/token")) issuerErrorKeepsUnknownReceipt("key_management_algorithm_mismatch",
            "Slot 9D needs an RSA-2048 key. Check the key in slot 9D, then create the CSR again.",
            "9D 槽位需要 RSA-2048 密钥。请核对 9D 的密钥后重新生成 CSR。", route, status = 422,
            selection = EnrollmentSelection(EnrollmentPurpose.KEY_MANAGEMENT))
    }

    @Test fun resultReportFailureKeepsUnknownAndExactMissingAcknowledgmentMessage() = issuerErrorKeepsUnknownReceipt(
        "future_error", "The issuer did not confirm receiving this result. Ask the issuing administrator to check the request record.",
        "发卡服务没有确认收到本次结果。请让发卡管理员核对申请记录。", route = "/result", status = 500)
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
            enrollmentIdentity = fixture.csr.identity, enrollmentSelection = selection, enrollmentRevision = model.enrollment.state.value.revision)
        model.enrollmentSelection(EnrollmentSelection(EnrollmentPurpose.KEY_MANAGEMENT))
        model.enrollmentSelection(selection)
        model.submitEnrollment(pending)
        assertTrue(pending.pin.all { it == '\u0000' })
        assertFalse(model.state.value.busy)
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
        assertNull(model.enrollment.state.value.csr)
    }
    @Test fun confirmationWithDifferentPersonIdentityCannotDispatchAtCurrentRevision() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val pending = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "123456".toCharArray(),
            enrollmentIdentity = fixture.identity.copy(employeeReference = "EMP-OTHER"),
            enrollmentRevision = model.enrollment.state.value.revision)
        model.submitEnrollment(pending)
        assertTrue(pending.pin.all { it == '\u0000' })
        assertFalse(model.state.value.busy)
        assertNull(model.enrollment.state.value.csr)
        assertEquals(FailureCode.INVALID_INPUT, (model.state.value.result as OperationResult.Failed).code)
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
        model.issueEnrollment(revision)
        assertFalse(model.enrollment.state.value.attempted)
        assertEquals(EnrollmentStage.CSR, model.enrollment.state.value.stage)
        assertFalse(model.state.value.busy)
    }
    @Test fun documentImportCannotOpenTlsRootPickerOrActualTlsConnection() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val selection = EnrollmentSelection(EnrollmentPurpose.DOCUMENT, 0x9c)
        model.enrollmentSelection(selection)
        val csr = fixture.csr(selection = selection)
        model.enrollment.settleCsr(model.enrollment.startCsr(), csr)
        val (owner, _) = model.enrollment.prepareIssuerRequest()
        val eku = org.bouncycastle.asn1.x509.KeyPurposeId.getInstance(org.bouncycastle.asn1.ASN1ObjectIdentifier(selection.purpose.eku))
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(purposes = arrayOf(eku, EnrollmentFixtures.oid("1.3.6.1.4.1.311.10.3.12"), EnrollmentFixtures.oid(EnrollmentPurpose.KEY_MANAGEMENT.eku)),
            usage = org.bouncycastle.asn1.x509.KeyUsage.digitalSignature or org.bouncycastle.asn1.x509.KeyUsage.nonRepudiation,
            identity = csr.identity, names = EnrollmentFixtures.emailNames(csr.identity)), fixture.intermediate), fixture.root, csr)
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
        EnrollmentHttpsServer(fixture) {
            assertTrue(card.closed)
            CardOperationGate.tryAcquire()!!.close()
            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
        }.use { server ->
            val model = model(fixture, server.address)
            val owner = model.enrollment.startCsr()
            val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "123456".toCharArray(), enrollmentIdentity = fixture.csr.identity)
            run(model, input, card); await(model)
            val csr = model.enrollment.state.value.csr!!
            assertEquals(owner, model.enrollment.generation)
            assertTrue(input.pin.all { it == '\u0000' })
            model.cancel()
            assertSame(csr, model.enrollment.state.value.csr)
            attachIssuer(model, fixture, server.address)
            model.issueEnrollment(); await(model)
            assertEquals("result=${model.state.value.result}; requests=${server.requests.get()}; server=${server.lastFailure}",
                EnrollmentStage.REJECTED, model.enrollment.state.value.stage)
            assertFalse(model.state.value.requiresInspection)
            assertEquals(1, server.requests.get())
        }
    }
    @Test fun queuedCancellationDoesNotDispatchOrSpendTheCsrAttempt() {
        val scheduler = TestCoroutineScheduler()
        mainDispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(mainDispatcher)
        val fixture = EnrollmentFixtures()
        EnrollmentHttpsServer(fixture) { error("Queued cancellation must not reach HTTP") }.use { server ->
            val model = model(fixture, server.address)
            model.enrollment.settleCsr(model.enrollment.startCsr(), fixture.csr)
            attachIssuer(model, fixture, server.address)
            repeat(2) {
                model.issueEnrollment()
                assertTrue(model.state.value.busy)
                model.cancel()
                scheduler.runCurrent()
                assertEquals(EnrollmentStage.CSR, model.enrollment.state.value.stage)
                assertFalse(model.enrollment.state.value.attempted)
                assertNull(model.enrollment.state.value.receipt)
                assertTrue(model.enrollment.state.value.unknownReceipts.isEmpty())
                assertFalse(model.state.value.busy)
            }
            assertEquals(0, server.requests.get())
            assertTrue(issuerFixtures.last().mutations.isEmpty())
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
            attachIssuer(model, fixture, server.address)
            model.issueEnrollment()
            try {
                val arrived = entered.await(5, TimeUnit.SECONDS)
                assertTrue("stage=${model.enrollment.state.value.stage}; result=${model.state.value.result}; requests=${server.requests.get()}; server=${server.lastFailure}", arrived)
                model.cancel()
                assertEquals(EnrollmentStage.UNKNOWN, model.enrollment.state.value.stage)
                assertFalse(model.state.value.requiresInspection)
                model.enrollmentSettings("https://different")
                model.enrollment.choosePerson(enrollmentTarget("https://issuer.example.test", fixture.identity.copy(displayName = "new")))
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
        run(model, OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "999999".toCharArray(), enrollmentIdentity = fixture.csr.identity), channel)
        assertTrue(entered.await(5, TimeUnit.SECONDS)); model.cancel(); release.countDown(); await(model)
        val result = model.state.value.result as OperationResult.Failed
        assertEquals(FailureCode.PIN_INVALID, result.code); assertEquals(0x63c2, result.status)
        assertNull(model.enrollment.state.value.csr)
    }
    @Test fun restoredVerifiedAndImportedIdentitySurviveInitialReaderSelectionButRetireOnNextChange() {
        val fixture = EnrollmentFixtures()
        for (stage in listOf(EnrollmentStage.VERIFIED, EnrollmentStage.IMPORTED)) {
            val original = model(fixture)
            val csr = fixture.csr
            val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(), fixture.intermediate), fixture.root, csr)
            original.enrollment.settleCsr(original.enrollment.startCsr(), csr)
            val (owner, receipt) = original.enrollment.prepareIssuerRequest()
            original.enrollment.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
            if (stage == EnrollmentStage.IMPORTED) original.enrollment.imported(owner)
            val restarted = ManagerViewModel(app).also(models::add)
            assertTrue(restarted.enrollment.state.value.restored)
            assertEquals(stage, restarted.enrollment.state.value.stage)
            restarted.selectReader(reader())
            assertEquals(stage, restarted.enrollment.state.value.stage)
            assertEquals(receipt.target, restarted.enrollment.state.value.target)
            val recovered = if (stage == EnrollmentStage.VERIFIED) restarted.enrollment.importRequest()
                else restarted.enrollment.importedIdentity()
            assertEquals(csr.identity, recovered.csr.identity)
            assertArrayEquals(csr.der, recovered.csr.der)
            assertArrayEquals(bundle.leaf, recovered.bundle.leaf)
            assertEquals(bundle.chain.size, recovered.bundle.chain.size)
            bundle.chain.zip(recovered.bundle.chain).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
            if (stage == EnrollmentStage.IMPORTED) assertTrue(runCatching { restarted.enrollment.importRequest() }.isFailure)
            restarted.selectReader(null)
            assertFalse(restarted.enrollment.state.value.restored)
            assertNull(restarted.enrollment.state.value.csr)
            assertNull(restarted.enrollment.state.value.bundle)
            assertTrue(runCatching { restarted.enrollment.importRequest() }.isFailure)
            assertTrue(runCatching { restarted.enrollment.importedIdentity() }.isFailure)
        }
    }
    @Test fun enrollmentImportRetainsUnknownAndAcceptedReadbackRecoveryFences() {
        val fixture = EnrollmentFixtures()
        for (lost in listOf(true, false)) {
            val model = model(fixture)
            val card = EnrollmentCard().apply { lostWrite = lost; corruptReadback = !lost }
            val csr = fixture.csr(key = card.keyPair)
            val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(key = card.keyPair), fixture.intermediate), fixture.root, csr)
            model.enrollment.settleCsr(model.enrollment.startCsr(), csr)
            val (owner, _) = model.enrollment.prepareIssuerRequest()
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
        val model = ManagerViewModel(app).also(models::add)
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
        val input = OperationInput(ManagerOperation.PIV_ENROLL_CSR, pin = "123456".toCharArray(), enrollmentIdentity = fixture.csr.identity)
        run(model, input, card)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.state.value.lastRetry == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("PIN" to "PUK", model.state.value.lastRetry)
        model.approveLastRetry(false); await(model)
        assertTrue(card.commands.none { it.ins == 0x20 })
        assertTrue(input.pin.all { it == '\u0000' })
    }
    private fun awaitReviewCompletion(model: ManagerViewModel) = runBlocking {
        withTimeout(10000) { model.state.first { !it.busy } }
    }

    @Test fun reviewPostCaCommitFailureRetainsVerifiedBundleSendsIssuedResultAndFinishes() {
        val fixture = EnrollmentFixtures()
        val leaf = fixture.leaf()
        val body = JsonObject().apply {
            add("certChain", com.google.gson.JsonArray().apply {
                add(EnrollmentProfile.pem(leaf)); add(EnrollmentProfile.pem(fixture.intermediate))
            })
        }.toString().toByteArray()
        EnrollmentHttpsServer(fixture, body) { "HTTP/1.1 201 Created\r\nContent-Length: ${body.size}\r\n\r\n" }.use { server ->
            val model = model(fixture, server.address)
            model.enrollment.settleCsr(model.enrollment.startCsr(), fixture.csr)
            val failing = EnrollmentFailingPreferences(app.getSharedPreferences("enrollment_settings", android.content.Context.MODE_PRIVATE))
            failing.reject = { key, value -> key == "issuance_receipts" && value!!.contains("\"stage\":\"VERIFIED\"") }
            ReflectionHelpers.setField(model.enrollment, "settingsStore", EnrollmentSettings(failing))
            val issuer = attachIssuer(model, fixture, server.address)
            model.issueEnrollment(); awaitReviewCompletion(model)
            assertEquals("received certificate stage", EnrollmentStage.VERIFIED, model.enrollment.state.value.stage)
            assertArrayEquals("retained verified leaf", leaf, model.enrollment.importRequest().bundle.leaf)
            assertEquals("CA requests", 1, server.requests.get())
            val report = issuer.mutations.single { JsonParser.parseString(it.third).asJsonObject.has("outcome") }
            assertEquals("reported outcome", "issued", JsonParser.parseString(report.third).asJsonObject["outcome"].asString)
            assertFalse("result acknowledged in memory", model.enrollment.state.value.receipt!!.reportPending)
            assertTrue("post-CA persistence failed", failing.failures > 0)
            assertNull("local persistence is not an issuer error", model.state.value.enrollmentError)
        }
    }

    @Test fun reviewImportedCommitFailureFinishesCardReadbackWithAppliedResult() {
        val fixture = EnrollmentFixtures()
        val model = model(fixture)
        val card = EnrollmentCard()
        val csr = fixture.csr(key = card.keyPair)
        val bundle = EnrollmentProfile.validate(listOf(fixture.leaf(key = card.keyPair), fixture.intermediate), fixture.root, csr)
        model.enrollment.settleCsr(model.enrollment.startCsr(), csr)
        val (owner, _) = model.enrollment.prepareIssuerRequest()
        model.enrollment.settleIssuance(owner, bundle, EnrollmentStage.VERIFIED)
        val failing = EnrollmentFailingPreferences(app.getSharedPreferences("enrollment_settings", android.content.Context.MODE_PRIVATE))
        failing.reject = { key, value -> key == "issuance_receipts" && value!!.contains("\"stage\":\"IMPORTED\"") }
        ReflectionHelpers.setField(model.enrollment, "settingsStore", EnrollmentSettings(failing))
        val input = OperationInput(ManagerOperation.PIV_ENROLL_IMPORT, adminKey = card.managementKey.copyOf(),
            enrollmentImport = model.enrollment.importRequest())
        run(model, input, card); awaitReviewCompletion(model)
        assertEquals("imported stage", EnrollmentStage.IMPORTED, model.enrollment.state.value.stage)
        assertEquals("completed write effect", Effect.APPLIED, (model.state.value.result as OperationResult.Completed).effect)
        assertArrayEquals("readback certificate", bundle.leaf, card.certificate)
        assertTrue("card channel closed", card.closed)
        assertTrue("synthetic management key cleared", input.adminKey.all { it == 0.toByte() })
        assertEquals("failed IMPORTED commits", 1, failing.failures)
    }

    @Test fun reviewReportFailureWithVerifiedBundleUsesNeutralUnreportedMessage() {
        val fixture = EnrollmentFixtures()
        val body = JsonObject().apply {
            add("certChain", com.google.gson.JsonArray().apply {
                add(EnrollmentProfile.pem(fixture.leaf())); add(EnrollmentProfile.pem(fixture.intermediate))
            })
        }.toString().toByteArray()
        EnrollmentHttpsServer(fixture, body) { "HTTP/1.1 201 Created\r\nContent-Length: ${body.size}\r\n\r\n" }.use { server ->
            val model = model(fixture, server.address)
            model.enrollment.settleCsr(model.enrollment.startCsr(), fixture.csr)
            attachIssuer(model, fixture, server.address, "/result", "future_error", 500)
            model.issueEnrollment(); awaitReviewCompletion(model)
            assertNotNull("verified bundle retained", model.enrollment.state.value.bundle)
            assertTrue(model.enrollment.state.value.receipt!!.reportPending)
            assertEquals(cc.cardkey.R.string.enrollment_resultUnreported, model.state.value.enrollmentError)
            val resources = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {
                setLocale(java.util.Locale.ENGLISH)
            }).resources
            assertEquals("The issuer did not confirm receiving this result. Ask the issuing administrator to check the request record.",
                resources.getString(model.state.value.enrollmentError!!))
        }
    }

}
