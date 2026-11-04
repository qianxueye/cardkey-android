package cc.cardkey.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class OutcomeWordingTest {
    @Test fun pinScopeNamesMissingResultAndNoRepeat() {
        assertReviewedCopy("protectedPiv_pinScope",
            "PIN 为 6–8 位数字。输错会减少剩余次数。未取得操作结果时，请先读取卡片状态，勿重复执行。",
            "The PIN must contain 6–8 digits. An incorrect entry reduces the remaining attempts. If no operation result is available, read the card status first. Do not repeat the operation.")
    }

    @Test fun secondScanRiskKeepsInterruptionConditionalAndNoAutomaticRetry() {
        assertReviewedCopy("protectedPiv_confirmRisk",
            "这次会发送 PIN。输错会减少剩余次数。若连接中断，请先读取卡片状态，不要自动重试。",
            "This scan sends the PIN. An incorrect entry reduces the remaining attempts. If the connection is interrupted, read the card status first. Do not retry automatically.")
    }

    @Test fun unknownOperationNamesNoUsableResultAndNoRepeat() {
        assertReviewedCopy("operation_unknown",
            "未取得可用的操作结果。请先读取卡片状态，勿重复执行原操作。",
            "No usable operation result was obtained. Read the card status first. Do not repeat the original operation.")
    }

    @Test fun tlsSignatureReturnedRequiresServerCheckAndNoResubmit() {
        assertReviewedCopy("tls_mayHaveSubmitted",
            "卡片已返回认证签名。请先核对服务器记录，勿重复提交。",
            "The card returned an authentication signature. Check the server records before proceeding. Do not submit again.")
    }

    @Test fun ordinaryInspectionNamesPendingCardCheckAndNoRepeat() {
        assertReviewedCopy("protectedPiv_ordinaryInspection",
            "上次操作后尚未核对原卡状态。请保持原连接方式，检查原卡片：在「设置」中打开原操作对应的「高级 FIDO 设置与诊断」并选择「FIDO2 · 读取」，或打开「高级 PIV 管理与诊断」并选择「查看 PIV 状态」。不要重复执行原操作。",
            "The original card has not been checked since the previous operation. Keep the original connection method and inspect the original card. In Settings (设置), open the Advanced FIDO settings and diagnostics page (高级 FIDO 设置与诊断) that matches the original operation and select FIDO2 · Read (FIDO2 · 读取), or open Advanced PIV management and diagnostics (高级 PIV 管理与诊断) and select View PIV status (查看 PIV 状态). Do not repeat the original operation.")
    }

    private fun assertReviewedCopy(key: String, chinese: String, english: String) {
        for ((directory, expected) in listOf("values" to chinese, "values-zh" to chinese, "values-en" to english)) {
            val matches = requireNotNull(File("src/main/res/$directory").listFiles())
                .filter { it.extension == "xml" }.flatMap { file ->
                    val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                        .getElementsByTagName("string")
                    (0 until nodes.length).map { nodes.item(it) }
                        .filter { it.attributes.getNamedItem("name").nodeValue == key }
                        .map { it.textContent }
                }
            assertEquals("$directory/$key", listOf(expected), matches)
        }
    }
}
