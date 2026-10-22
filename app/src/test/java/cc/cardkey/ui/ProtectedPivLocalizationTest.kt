package cc.cardkey.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cc.cardkey.R
import cc.cardkey.protocol.*
import cc.cardkey.protocol.piv.*
import com.google.gson.JsonParser
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECPrivateKeySpec
import java.util.Date
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import cc.cardkey.protectedpiv.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectedPivLocalizationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(locale: String, state: ProtectedPivState, inspection: Boolean = false,
                       protectedInspection: Boolean = false, check: () -> Unit) {
        renderContent(locale, {
            ProtectedPivPanel(state, false, inspection, 0, {}, {}, { _, _, _ -> }, {}, protectedInspection)
        }, check)
    }

    private fun renderContent(locale: String, content: @Composable () -> Unit, check: () -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val config = Configuration(host.get().resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
        val context = host.get().createConfigurationContext(config)
        host.get().setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        content()
                    }
                }
            }
        }
        try { check() } finally { host.pause().stop().destroy() }
    }

    @Test fun englishPanelShowsRecognitionAndProtectedRecoveryWithoutChangingScope() {
        render("en-US", ProtectedPivState(profileReady = true, issuerSubject = "CN=Synthetic", issuerFingerprint = "abc",
            fileName = "sample.bin", fileSize = 3), inspection = true, protectedInspection = true) {
            compose.onNodeWithText("Protected PIV").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Issuer: CN=Synthetic").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("File: sample.bin · 3 B").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("The previous operation outcome is unknown. Import the original card configuration again, then read its protected status. Do not repeat the original operation.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("The first scan checks only the card identity, slot and remaining PIN attempts. It does not send the PIN.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-pin").assertDoesNotExist()
        }
    }

    @Test fun englishConfirmationPreservesLastAttemptErasureAndUnknownOutcomeWarnings() {
        render("en-US", ProtectedPivState(generation = 1, profileReady = true, awaitingPin = true,
            action = ProtectedPivAction.VERIFY_PIN, retries = 1, erasesKeysOnFailure = true)) {
            compose.onNodeWithText("Pending: Verify PIN · slot 9A").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Only one PIN attempt remains and the PUK is blocked. If this verification fails, the PIV private keys will be erased. Continue?").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-pin").performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("protected-piv-execute").performScrollTo().performClick()
            compose.onNodeWithText("This scan sends the PIN. An incorrect entry reduces the remaining attempts. A connection interruption may leave the outcome unknown. Do not retry automatically.").assertIsDisplayed()
            compose.onNodeWithText("Only 1 attempt remains. An incorrect entry may require PIN recovery.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        }
    }

    @Test fun englishOrdinaryRecoveryNamesTheOriginalInspectionRoute() {
        render("en-US", ProtectedPivState(), inspection = true) {
            compose.onNodeWithText("The previous operation outcome is unknown. Keep the original connection method and inspect the original card.", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Do not repeat the original operation.", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-pin").assertDoesNotExist()
        }
    }

    private fun statusReport(): ManagerReport {
        val fixture = JsonParser.parseString(File("../fixtures/cs7-v1.json").readText()).asJsonObject
        fun bytes(name: String) = fixture[name].asString.hexBytes()
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.valueOf(3), Cs7.curve))
        val name = X500Name("CN=Synthetic issuer")
        val issuer = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(1000), name, Cs7.publicKey(bytes("issuer_point")))
            .addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(bytes("issuer_id") + ByteArray(12)))
            .build(JcaContentSignerBuilder("SHA384withECDSA").build(privateKey)).encoded
        val profile = """{"guid":"${fixture["guid"].asString}","issuer_id":"${fixture["issuer_id"].asString}","card_public":"${fixture["card_point"].asString}","pairing":"12345678"}""".toByteArray()
        val flow = ProtectedPivWorkflow()
        flow.receiveDocument(requireNotNull(flow.requestDocument(ProtectedPivDocument.ISSUER)), issuer)
        flow.receiveDocument(requireNotNull(flow.requestDocument(ProtectedPivDocument.PROFILE)), profile)
        val input = flow.recognize(ProtectedPivAction.STATUS, 0x9a, "")
        try {
            val outcome = CardSession(PivSecureContextTest().Peer(dynamic = true)).run { ManagerOperations().execute(it, input) }
            flow.settle(requireNotNull(input.protectedScan), outcome)
            return (outcome as OperationResult.Completed).value
        } finally { input.close(); flow.retire(); profile.fill(0) }
    }

    @Test fun actualAuthenticatedStatusRowsRenderInEnglishWithOriginalEffects() {
        val report = statusReport()
        assertNotNull(report.protectedPivIdentity)
        assertEquals(5, report.rows.single { it.label == "authenticated_pin_retries" }.valueArguments.single())
        renderContent("en-US", {
            report.rows.forEach { ResultFactRow(it) }
            ConfirmationPolicy.resultMessages(OperationResult.Completed(report, Effect.NONE), CardWire.APDU).forEach {
                androidx.compose.material3.Text(stringResource(it))
            }
        }) {
            compose.onNodeWithText("PIN status").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Not verified").assertIsDisplayed()
            compose.onNodeWithText("Remaining PIN attempts read during this authentication").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("The operation is complete.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Version").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun actualCertificateRowsKeepChinesePresentationAndTechnicalKeys() {
        val fixture = CertificateLifecycleFixture()
        val report = (fixture.read({ fixture.start + 1 }) as OperationResult.Completed).value
        assertEquals("valid", report.rows.single { it.label == "piv_date_status" }.value)
        assertArrayEquals(fixture.der, report.export)
        renderContent("zh-CN", { report.rows.forEach { ResultFactRow(it) } }) {
            compose.onNodeWithText("日期状态").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("有效期内").assertIsDisplayed()
            compose.onNodeWithText("公钥").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("PIN 验证策略").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun chineseDefaultsAndLocalePlaceholdersRemainExact() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        fun resources(locale: String) = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
        }).resources
        val chinese = resources("zh-CN")
        val english = resources("en-US")
        val expected = mapOf(
            "protectedPiv_title" to "受保护的 PIV",
            "protectedPiv_introduction" to "先选择公开签发者证书；如需签名，再选择文件；最后选择私有配置。配置仅保存在内存中，离开应用后清除。",
            "protectedPiv_chooseIssuer" to "选择签发者证书",
            "protectedPiv_issuer" to "签发者：%1\$s",
            "protectedPiv_fingerprint" to "证书指纹（SHA-256）：%1\$s",
            "protectedPiv_issuerScope" to "此证书提供已登记的公钥和标识；不会验证证书链、有效期或吊销状态，也不会加入系统信任。",
            "protectedPiv_chooseFile" to "选择原始签名文件",
            "protectedPiv_file" to "文件：%1\$s · %2\$d 字节",
            "protectedPiv_chooseProfile" to "选择私有配置 JSON",
            "protectedPiv_pickerScope" to "打开任何文件选择器都会清除现有私有配置。不要分享私有配置或配对码。",
            "protectedPiv_ready" to "配置已就绪；尚不能据此确认当前卡片身份。",
            "protectedPiv_guid" to "配置中的卡片 GUID：%1\$s",
            "protectedPiv_inspection" to "上次操作结果未知。请重新导入原卡片的配置，再读取受保护状态；不要重复执行原操作。",
            "protectedPiv_operation" to "受保护操作",
            "protectedPiv_slot" to "受保护槽位",
            "protectedPiv_csrSubject" to "CSR 主题（例如 CN=CardKey）",
            "protectedPiv_recognitionScope" to "首次扫描只核对卡片身份、槽位和 PIN 剩余次数，不会发送 PIN。",
            "protectedPiv_recognize" to "识别卡片并读取",
            "protectedPiv_recognized" to "首次扫描已完成。请先移开卡片，再输入 PIN 并确认第二次扫描。",
            "protectedPiv_pending" to "待执行：%1\$s · 槽位 %2\$s",
            "protectedPiv_retries" to "PIN 剩余次数：%1\$d",
            "protectedPiv_erasure" to "PIN 仅剩一次重试，PUK 已锁定。此次验证失败将清除 PIV 私钥。确认继续？",
            "protectedPiv_lastRetry" to "PIN 仅剩 1 次机会。再次输错可能锁定 PIN；恢复需要接触式连接。",
            "protectedPiv_pin" to "PIN",
            "protectedPiv_pinScope" to "PIN 为 6–8 位数字。输错会减少剩余次数；结果不明时请勿重复执行。",
            "protectedPiv_execute" to "确认执行…",
            "protectedPiv_clear" to "清除配置并取消待执行操作",
            "protectedPiv_contactScope" to "修改 PIN／PUK、管理密钥、生成或导入密钥、重置、CA 证书导入、CMS 和 TLS 仍需接触式连接。",
            "protectedPiv_confirmTitle" to "确认第二次扫描",
            "protectedPiv_confirmOperation" to "执行 %1\$s，槽位 %2\$s。再次扫描同一张卡；身份、槽位或剩余次数有变化时会停止。",
            "protectedPiv_confirmRisk" to "这次会发送 PIN。输错会减少剩余次数；连接中断可能使结果无法确认。不要自动重试。",
            "protectedPiv_confirmLastRetry" to "仅剩 1 次机会；输错后可能需要恢复 PIN。",
            "protectedPiv_scanAgain" to "开始第二次扫描",
            "protectedPiv_cancel" to "取消",
            "protectedPiv_actionStatus" to "状态",
            "protectedPiv_actionSlot" to "槽位与证书",
            "protectedPiv_actionSsh" to "导出 SSH 公钥",
            "protectedPiv_actionPin" to "验证 PIN",
            "protectedPiv_actionCsr" to "导出 CSR",
            "protectedPiv_actionSign" to "原始文件签名",
            "protectedPiv_actionEcdh" to "验证 ECDH",
            "protectedPiv_actionRsa" to "验证 RSA 解密",
            "protectedPiv_pinStatus" to "PIN 状态",
            "protectedPiv_contactRequired" to "需要接触式连接恢复",
            "protectedPiv_verified" to "当前会话已验证",
            "protectedPiv_unverified" to "尚未验证",
            "protectedPiv_authenticatedRetries" to "本次认证读取的 PIN 剩余重试次数",
            "protectedPiv_count" to "%1\$d 次",
            "protectedPiv_rowVersion" to "版本",
            "protectedPiv_rowSerial" to "序列号",
            "protectedPiv_rowPinRetries" to "PIN 剩余重试次数",
            "protectedPiv_rowPukRetries" to "PUK 剩余重试次数",
            "protectedPiv_rowRetries" to "剩余 %1\$d 次（共 %2\$d 次）",
            "protectedPiv_rowManagementKey" to "管理密钥",
            "protectedPiv_rowSlot" to "槽位",
            "protectedPiv_rowCertificate" to "证书",
            "protectedPiv_rowNoCertificate" to "未存储证书",
            "protectedPiv_rowDateStatus" to "日期状态",
            "protectedPiv_rowInvalidDates" to "有效期起止顺序异常",
            "protectedPiv_rowNotYetValid" to "尚未生效",
            "protectedPiv_rowExpired" to "已过期",
            "protectedPiv_rowValid" to "有效期内",
            "protectedPiv_rowIssuer" to "签发者",
            "protectedPiv_rowCertificateSerial" to "证书序列号（十六进制）",
            "protectedPiv_rowNotBefore" to "生效时间（UTC）",
            "protectedPiv_rowNotAfter" to "到期时间（UTC）",
            "protectedPiv_rowAssessedAt" to "检查时间（设备时钟，UTC）",
            "protectedPiv_rowAlgorithm" to "算法",
            "protectedPiv_rowPinPolicy" to "PIN 验证策略",
            "protectedPiv_rowNoPin" to "无需 PIN",
            "protectedPiv_rowPinOnce" to "会话内验证一次",
            "protectedPiv_rowPinAlways" to "每次操作验证",
            "protectedPiv_rowTouchPolicy" to "触摸确认策略",
            "protectedPiv_rowNoTouch" to "无需触摸",
            "protectedPiv_rowTouchAlways" to "每次操作触摸",
            "protectedPiv_rowTouchCached" to "允许缓存触摸确认",
            "protectedPiv_rowPublicKey" to "公钥",
            "protectedPiv_rowCsr" to "CSR",
            "protectedPiv_operationStatus" to "查看 PIV 状态",
            "protectedPiv_operationSlot" to "查看证书",
            "protectedPiv_operationSign" to "文件签名",
            "protectedPiv_operationEcdh" to "ECDH · 验证",
            "protectedPiv_operationRsa" to "RSA · 验证",
            "protectedPiv_export" to "导出",
        )
        val placeholders = Regex("%[0-9]+\\$[sd]")
        for ((key, value) in expected) {
            val id = chinese.getIdentifier(key, "string", app.packageName)
            assertNotEquals(key, 0, id)
            assertEquals(key, value, chinese.getString(id))
            assertEquals(key, placeholders.findAll(value).map { it.value }.toList(),
                placeholders.findAll(english.getString(id)).map { it.value }.toList())
        }
        assertEquals("文件：sample · 1 字节", chinese.getString(R.string.protectedPiv_file, "sample", 1))
        assertEquals("File: sample · 1 B", english.getString(R.string.protectedPiv_file, "sample", 1))
        assertEquals("1", english.getString(R.string.protectedPiv_count, 1))
        assertEquals("PIN 仅剩一次重试，PUK 已锁定。此次验证失败将清除 PIV 私钥。确认继续？", chinese.getString(R.string.protectedPiv_erasure))
    }
}
