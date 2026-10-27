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
            compose.onNodeWithText("Import the original card configuration again, then read its protected status. Do not repeat the original operation before reading the status.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-recognize").performScrollTo().assertIsDisplayed()
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
            compose.onNodeWithText("Only 1 attempt remains. An incorrect entry may require PIN recovery.").assertDoesNotExist()
            compose.onNodeWithText("Action: Verify PIN, slot 9A. Scan the same card again. The operation stops if the identity, slot or remaining attempts have changed.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        }
    }

    @Test fun nonErasureLastAttemptStillShowsLockoutAndRecoveryAtConfirmation() {
        render("en-US", ProtectedPivState(generation = 1, profileReady = true, awaitingPin = true,
            action = ProtectedPivAction.VERIFY_PIN, retries = 1, erasesKeysOnFailure = false)) {
            compose.onNodeWithText("Only 1 PIN attempt remains. Another incorrect entry may block the PIN. Recovery requires a contact connection.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-pin").performScrollTo().performTextInput("123456")
            compose.onNodeWithTag("protected-piv-execute").performScrollTo().performClick()
            compose.onNodeWithText("Only 1 attempt remains. An incorrect entry may require PIN recovery.").assertIsDisplayed()
            compose.onNodeWithText("This scan sends the PIN. An incorrect entry reduces the remaining attempts. A connection interruption may leave the outcome unknown. Do not retry automatically.").assertIsDisplayed()
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
            compose.onNodeWithText("The PIN has not been verified in this session").assertIsDisplayed()
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
            "user_updateHelp" to "此卡仅支持修改用户名，不支持修改显示名称。",
            "confirmTitle_FIDO_DELETE" to "删除通行密钥",
            "confirmTitle_FIDO_ALWAYS_UV" to "始终验证用户",
            "confirmTitle_FIDO_MIN_PIN" to "PIN · 最短长度",
            "confirmTitle_FIDO_ENTERPRISE" to "企业证明 · 启用",
            "confirmTitle_FIDO_REGISTER" to "注册 · 验证",
            "confirmTitle_U2F_REGISTER" to "U2F · 注册",
            "confirmTitle_FIDO_ASSERT" to "签名 · 验证",
            "confirmTitle_FIDO_VERIFY_CREDENTIAL" to "验证签名",
            "confirmTitle_FIDO_BLOB_READ" to "扩展数据 · 读取",
            "confirmTitle_FIDO_BLOB_WRITE" to "扩展数据 · 设置",
            "confirmTitle_FIDO_RESET" to "FIDO2 · 重置",
            "confirmTitle_PIV_ADMIN" to "管理密钥 · 修改",
            "confirmTitle_PIV_GENERATE" to "生成密钥",
            "confirmTitle_PIV_IMPORT" to "导入证书",
            "confirmTitle_PIV_ENROLL_IMPORT" to "导入证书",
            "confirmTitle_PIV_ENROLL_CSR" to "生成 CSR",
            "confirmTitle_PIV_RESET" to "PIV · 重置",
            "confirmTitle_PIV_CHANGE_PIN" to "更改 PIV PIN",
            "confirmTitle_PIV_CHANGE_PUK" to "更改 PUK",
            "confirmTitle_PIV_UNBLOCK" to "解锁 PIV PIN",
            "confirmTitle_PIV_CMS_SIGN" to "CMS 文件签名",
            "protectedPiv_title" to "受保护的 PIV",
            "protectedPiv_introduction" to "先选择公开签发者证书；如需签名，再选择文件；最后选择私有配置。配置仅保存在内存中，离开应用后清除。",
            "protectedPiv_chooseIssuer" to "选择签发者证书",
            "protectedPiv_issuer" to "签发者：%1\$s",
            "protectedPiv_fingerprint" to "证书指纹（SHA-256）：%1\$s",
            "protectedPiv_chooseFile" to "选择原始签名文件",
            "protectedPiv_file" to "文件：%1\$s · %2\$d 字节",
            "protectedPiv_chooseProfile" to "选择私有配置 JSON",
            "protectedPiv_pickerScope" to "打开任何文件选择器都会清除现有私有配置。不要分享私有配置或配对码。",
            "protectedPiv_ready" to "配置已就绪。",
            "protectedPiv_guid" to "配置中的卡片 GUID：%1\$s",
            "protectedPiv_inspection" to "请重新导入原卡片的配置，再读取受保护状态；读取前不要重复执行原操作。",
            "protectedPiv_operation" to "受保护操作",
            "protectedPiv_slot" to "受保护槽位",
            "protectedPiv_csrSubject" to "CSR 主题（例如 CN=CardKey）",
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
            "protectedPiv_unverified" to "本次会话未验证 PIN",
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
            "document_saved" to "文件已保存。",
            "error_authorization" to "卡片未授权此操作。",
            "error_pinRequired" to "此操作需要 PIN 验证。",
            "error_cancelled" to "操作已取消。",
            "error_capacityExceeded" to "卡片存储空间不足，或已达到此操作的数量或大小限制。",
            "error_cardRejected" to "卡片拒绝了此操作。请检查卡片状态及操作前提。",
            "error_contactRequired" to "此操作需要接触式读卡器。",
            "error_invalidInput" to "输入格式有误，请检查所填内容。",
            "error_keyMismatch" to "证书公钥与所选槽位不匹配，未导入证书。",
            "error_malformed" to "卡片响应无效。",
            "error_nfcDisabled" to "NFC 未开启。请开启 NFC，或连接受支持的 USB 读卡器。",
            "error_nfcUnavailable" to "此设备不支持 NFC。请连接受支持的 USB 读卡器。",
            "error_physicalActionRequired" to "请拔出并重新插入卡片；使用 NFC 时，请移开卡片后重新贴近。",
            "error_pinBlocked" to "PIN 或 PUK 已锁定。",
            "error_pinInvalid" to "PIN 或 PUK 不正确。请确认后重新发起操作。",
            "error_recoveryRequired" to "卡片状态与预期不符。请先读取卡片状态，勿重复提交更改。",
            "error_transport" to "卡片连接失败。",
            "error_unsupported" to "此卡片或读卡器不支持这项操作。",
            "operation_appliedReadbackFailed" to "卡片已接受更改，但回读未完成。请读取卡片状态，勿重复提交更改。",
            "operation_completed" to "操作已完成。",
            "operation_notDispatched" to "指令未发送到卡片。",
            "operation_unknown" to "指令已发送，但未收到结果。更改可能已生效；请先读取卡片状态，再决定下一步。",
            "protectedPiv_ordinaryInspection" to "上次操作结果未知。请保持原连接方式，检查原卡片：在「设置」中打开原操作对应的「高级 FIDO 设置与诊断」并选择「FIDO2 · 读取」，或打开「高级 PIV 管理与诊断」并选择「查看 PIV 状态」。不要重复执行原操作。",
            "reader_nfc" to "选择操作后，将卡片贴近手机。",
            "signing_exportCancelled" to "已取消导出。",
            "signing_exportScope" to "导出的是所选文件的独立签名。",
            "signing_exportUnknown" to "未收到文件保存结果。请检查所选位置中的文件。",
            "signing_exported" to "已导出签名。",
            "signing_verified" to "签名验证：通过",
            "ssh_publicKeyExported" to "已导出 SSH 公钥。",
            "ssh_publicKeyScope" to "已读取 SSH 公钥。导出后可交给 SSH 服务管理员；私钥仍在卡内。",
            "workspace_details" to "技术详情",
            "workspace_outputFile" to "本次导出文件",
            "workspace_receipt" to "任务结果",
            "native_back" to "返回",
            "native_card" to "卡片",
            "native_unread" to "尚未读取",
            "native_observed" to "信息已读取",
            "native_app_unread" to "未读取",
            "native_app_read" to "已读取",
            "native_read" to "读取卡片",
            "native_read_scope" to "读取 FIDO 公开信息；证书信息请到“证书与签名”读取。",
            "native_tap" to "贴近手机的 NFC 感应区",
            "native_usb" to "选择已连接的 USB 读卡器",
            "native_reader_missing" to "未选择读卡器",
            "native_choose_reader" to "选择读卡器",
            "native_choose_reader_hint" to "选择 NFC 或已连接的 USB 读卡器",
            "native_prepare" to "准备 PIN",
            "native_inventory_title" to "查看通行密钥",
            "native_target_unread" to "目标卡片尚未识别",
            "native_target_observed" to "已在此连接读取 FIDO 信息",
            "native_pin_tap" to "可先输入 PIN，再提交并贴卡。",
            "native_pin_usb" to "可先输入 PIN，再提交读取。",
            "native_submit_tap" to "提交并贴卡",
            "native_certificates" to "证书与签名",
            "native_technical" to "技术信息",

            "fido_associatedModel" to "关联型号",
            "fido_modelMissing" to "未收录",
            "fido_modelLoading" to "正在查询…",
            "fido_modelFailed" to "型号查询失败",
            "manager_fido_unread" to "尚未读取 FIDO 信息",
            "manager_fido_observed" to "已读取 FIDO 信息",
            "manager_read_fido" to "读取 FIDO 信息",
            "manager_fido_intro" to "可先读取信息，或输入 FIDO PIN 查看通行密钥。",
            "manager_fido_advanced" to "高级设置与诊断",
            "manager_inventory_action" to "查看通行密钥",
        )
        val placeholders = Regex("%[0-9]+\\$[sd]")
        fun entries(file: File): Map<String, org.w3c.dom.Element> {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(file)
            assertFalse(file.path, document.documentElement.getAttributeNS("http://schemas.android.com/tools", "ignore")
                .split(',').contains("MissingTranslation"))
            val nodes = document.getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val node = nodes.item(index) as org.w3c.dom.Element
                node.getAttribute("name") to node
            }
        }
        val englishEntries = entries(File("src/main/res/values-en/protected_piv.xml"))
        assertEquals(expected.keys, englishEntries.keys)
        val defaults = requireNotNull(File("src/main/res/values").listFiles()).filter { it.extension == "xml" }
            .flatMap { entries(it).entries }.associate { it.key to it.value }
        val expectedFallbackKeys = setOf(
            "artwork_failed", "artwork_generic", "artwork_loading", "artwork_retry",
            "blob_help", "confirm_adminChange", "confirm_alwaysUvDisable", "confirm_alwaysUvEnable",
            "confirm_blobExport", "confirm_blobWrite", "confirm_certificateImport", "confirm_credentialDelete",
            "confirm_enterprise", "confirm_fidoLastRetry", "confirm_fidoReset", "confirm_keyGenerate",
            "confirm_lastRetryErasesKeys", "confirm_minPin", "confirm_pivReset", "confirm_registration",
            "confirm_secretRead", "confirm_secretShow", "credential_deleted", "credential_updated",
            "device_reader", "document_cancelled", "document_readFailed", "document_unknown",
            "enrollment_address", "enrollment_chooseRoot", "enrollment_cn", "enrollment_confirmImport",
            "enrollment_createCsr", "enrollment_csrReady", "enrollment_document", "enrollment_document9a",
            "enrollment_documentScope", "enrollment_exportCsr", "enrollment_imported", "enrollment_invalid",
            "enrollment_issue", "enrollment_issuing", "enrollment_purpose", "enrollment_rejected",
            "enrollment_scope", "enrollment_steps", "enrollment_title", "enrollment_token",
            "enrollment_unknown", "enrollment_verified", "enterprise_mode", "error_hidPhysicalActionRequired",
            "error_hidPowerCycleRequired", "error_pinChangeRequired", "error_pinSetupRequired", "inventory_empty",
            "inventory_failed", "inventory_loaded", "inventory_loading", "inventory_unread",
            "minPin_rps", "minPin_rpsHelp", "navigation_certificates", "navigation_device",
            "navigation_passkeys", "navigation_settings", "operation_fidoResetReadback", "plexo_ca_csr",
            "plexo_ca_currentRequest", "plexo_ca_documentIdentity", "plexo_ca_exportChain", "plexo_ca_identity",
            "plexo_ca_importExport", "plexo_ca_importReview", "plexo_ca_issue", "plexo_ca_prepare",
            "plexo_ca_purpose", "plexo_ca_root", "plexo_ca_rootReady", "plexo_ca_service",
            "plexo_ca_slotAlgorithm", "plexo_ca_tlsIdentity", "plexo_ca_unknownRequest", "plexo_provider_account",
            "plexo_provider_accounts", "plexo_provider_caller", "plexo_provider_cancel", "plexo_provider_close",
            "plexo_provider_create_consequence", "plexo_provider_create_title", "plexo_provider_executing", "plexo_provider_get_consequence",
            "plexo_provider_get_title", "plexo_provider_keep_nfc", "plexo_provider_permission", "plexo_provider_permission_prompt",
            "plexo_provider_reader", "plexo_provider_recognition_nfc", "plexo_provider_recognizing", "plexo_provider_rejected",
            "plexo_provider_result", "plexo_provider_rp", "plexo_provider_verified", "plexo_provider_verifying",
            "plexo_tls_certificate", "plexo_tls_result", "plexo_tls_service", "provider_accountNumber",
            "provider_attestation_direct", "provider_attestation_none", "provider_choose_account", "provider_createAction",
            "provider_credentialId", "provider_finalAttemptCreate", "provider_finalAttemptGet", "provider_getAction",
            "provider_pin", "provider_pinChange", "provider_pinSetup", "provider_remainingAttempts",
            "provider_request_rejected", "provider_settings", "provider_unlock", "provider_userId",
            "provider_username", "reader_empty", "reader_permissionDenied", "reader_usbPermission",
            "restriction_fidoReset", "secret_view", "settings_enrollment", "settings_provider",
            "settings_services", "signing_chooseFile", "signing_cmsRequirements", "signing_cmsScope",
            "signing_cmsVerified", "signing_limit", "signing_selectedFile",
            "status_hidUserPresenceRequired", "tls_cancelled", "tls_chooseServerRoot", "tls_confirm",
            "tls_endpoint", "tls_endpointHelp", "tls_failed", "tls_identity",
            "tls_mayHaveSubmitted", "tls_running", "tls_serverRoot",
            "tls_serverRootReady", "tls_start", "tls_success",
            "tls_timeout", "tls_title", "tls_unknown", "tls_validUntil",
            "workspace_advanced", "workspace_authenticator", "workspace_common",
            "workspace_createOnWebsite", "workspace_enrollmentFlow", "workspace_fidoPin", "workspace_inspection",
            "workspace_objects", "workspace_pivReaderRequired", "workspace_pivSecurity", "workspace_readSites",
            "workspace_security", "workspace_selectedObject", "workspace_selectedSlot", "workspace_sites",
            "workspace_slotAuthentication", "workspace_slotCardAuthentication", "workspace_slotCatalog",
            "workspace_slotManagement", "workspace_slotRetired", "workspace_slotSignature", "workspace_tasks",
        )
        val fallbackKeys = defaults.filterValues { it.getAttributeNS("http://schemas.android.com/tools", "ignore")
            .split(',').contains("MissingTranslation") }.keys
        assertEquals(expectedFallbackKeys, fallbackKeys)
        assertTrue(expected.keys.intersect(fallbackKeys).isEmpty())
        for ((key, value) in expected) {
            assertFalse(key, requireNotNull(defaults[key]).getAttributeNS("http://schemas.android.com/tools", "ignore")
                .split(',').contains("MissingTranslation"))
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
