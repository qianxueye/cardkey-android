package cc.cardkey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.cardkey.protectedpiv.*
import cc.cardkey.protocol.piv.PivClient
import androidx.compose.ui.res.stringResource
import cc.cardkey.R

@Composable
fun ProtectedPivPanel(
    state: ProtectedPivState,
    blocked: Boolean,
    inspectionRequired: Boolean,
    clearInputs: Int,
    onDocument: (ProtectedPivDocument) -> Unit,
    onClear: () -> Unit,
    onRecognize: (ProtectedPivAction, Int, String) -> Unit,
    onExecute: (CharArray) -> Unit,
    protectedInspectionRequired: Boolean = false,
) {
    var action by remember { mutableStateOf(ProtectedPivAction.STATUS) }
    var slot by remember { mutableIntStateOf(0x9a) }
    var subject by remember { mutableStateOf("") }
    var pin by remember(state.generation, state.awaitingPin, inspectionRequired, clearInputs) { mutableStateOf("") }
    var confirm by remember(state.generation, state.awaitingPin, inspectionRequired, clearInputs) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state.generation, state.awaitingPin, inspectionRequired, clearInputs) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { pin = ""; confirm = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pin = ""; confirm = false }
    }
    val pendingAction = state.action?.let(::protectedPivActionTitle).orEmpty()
    val pendingSlot = "%02X".format(state.slot)
    val erasureWarning = stringResource(R.string.confirm_lastRetryErasesKeys)
        .replace("{当前验证项}", "PIN").replace("{另一项}", "PUK")
    val enabled = !blocked && state.documentId == null
    val selecting = enabled && (!state.awaitingPin || inspectionRequired)
    val selectedAction = if (inspectionRequired) ProtectedPivAction.STATUS else action
    Surface(Modifier.fillMaxWidth().testTag("protected-piv-panel"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("受保护的 PIV", style = MaterialTheme.typography.titleMedium)
            Text("先选择公开签发者证书；如需签名，再选择文件；最后选择私有配置。配置仅保存在内存中，离开应用后清除。",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { pin = ""; confirm = false; onDocument(ProtectedPivDocument.ISSUER) },
                enabled = enabled, modifier = Modifier.testTag("protected-piv-issuer")) { Text("选择签发者证书") }
            state.issuerSubject?.let { Text("签发者：${protectedPivDisplayText(it)}") }
            state.issuerFingerprint?.let { Text("证书指纹（SHA-256）：$it", style = MaterialTheme.typography.bodySmall) }
            Text("此证书提供已登记的公钥和标识；不会验证证书链、有效期或吊销状态，也不会加入系统信任。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pin = ""; confirm = false; onDocument(ProtectedPivDocument.SIGNING_FILE) },
                enabled = enabled && state.issuerSubject != null, modifier = Modifier.testTag("protected-piv-file")) { Text("选择原始签名文件") }
            state.fileName?.let { Text("文件：$it · ${state.fileSize ?: 0} 字节") }
            OutlinedButton(onClick = { pin = ""; confirm = false; onDocument(ProtectedPivDocument.PROFILE) },
                enabled = enabled && state.issuerSubject != null, modifier = Modifier.testTag("protected-piv-profile")) { Text("选择私有配置 JSON") }
            Text("打开任何文件选择器都会清除现有私有配置。不要分享私有配置或配对码。", style = MaterialTheme.typography.bodySmall)
            if (state.profileReady) {
                Text("配置已就绪；尚不能据此确认当前卡片身份。", modifier = Modifier.testTag("protected-piv-ready"))
                state.guid?.let { Text("配置中的卡片 GUID：$it", style = MaterialTheme.typography.bodySmall) }
            }
            if (inspectionRequired) Text(if (protectedInspectionRequired)
                "上次操作结果未知。请重新导入原卡片的配置，再读取受保护状态；不要重复执行原操作。"
                else stringResource(R.string.protectedPiv_ordinaryInspection), color = MaterialTheme.colorScheme.error)
            Choice("受保护操作", selectedAction, if (inspectionRequired) listOf(ProtectedPivAction.STATUS) else ProtectedPivAction.entries, ::protectedPivActionTitle, selecting) { action = it; pin = ""; confirm = false }
            Choice("受保护槽位", slot, PivClient.slots, { "%02X".format(it) }, selecting) { slot = it; pin = ""; confirm = false }
            if (selectedAction == ProtectedPivAction.CSR) OutlinedTextField(subject, { subject = it },
                label = { Text("CSR 主题（例如 CN=CardKey）") }, enabled = selecting, modifier = Modifier.fillMaxWidth())
            if (!state.awaitingPin || inspectionRequired) {
                Text("首次扫描只核对卡片身份、槽位和 PIN 剩余次数，不会发送 PIN。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pin = ""; confirm = false; onRecognize(selectedAction, slot, subject) },
                    enabled = selecting && state.profileReady && (selectedAction != ProtectedPivAction.RAW_SIGN || state.fileSize != null) &&
                        (selectedAction != ProtectedPivAction.CSR || subject.isNotBlank()), modifier = Modifier.testTag("protected-piv-recognize")) {
                    Text("识别卡片并读取")
                }
            } else {
                Text("首次扫描已完成。请先移开卡片，再输入 PIN 并确认第二次扫描。")
                Text("待执行：${pendingAction} · 槽位 ${pendingSlot}")
                state.retries?.let { Text("PIN 剩余次数：$it") }
                if (state.erasesKeysOnFailure) Text(erasureWarning, color = MaterialTheme.colorScheme.error)
                if (state.retries == 1) Text("PIN 仅剩 1 次机会。再次输错可能锁定 PIN；恢复需要接触式连接。", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(pin, { pin = it }, label = { Text("PIN") }, singleLine = true,
                    enabled = enabled, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("protected-piv-pin"))
                Text("PIN 为 6–8 位数字。输错会减少剩余次数；结果不明时请勿重复执行。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirm = true }, enabled = enabled && pin.length in 6..8 && pin.all { it in '0'..'9' },
                    modifier = Modifier.testTag("protected-piv-execute")) { Text("确认执行…") }
            }
            OutlinedButton(onClick = { pin = ""; confirm = false; onClear() }, enabled = !blocked,
                modifier = Modifier.testTag("protected-piv-clear")) { Text("清除配置并取消待执行操作") }
            Text("修改 PIN／PUK、管理密钥、生成或导入密钥、重置、CA 证书导入、CMS 和 TLS 仍需接触式连接。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm && state.awaitingPin && enabled && !inspectionRequired) AlertDialog(
        onDismissRequest = { confirm = false; pin = "" },
        title = { Text("确认第二次扫描") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("执行 ${pendingAction}，槽位 ${pendingSlot}。再次扫描同一张卡；身份、槽位或剩余次数有变化时会停止。")
            Text("这次会发送 PIN。输错会减少剩余次数；连接中断可能使结果无法确认。不要自动重试。")
            if (state.erasesKeysOnFailure) Text(erasureWarning)
            if (state.retries == 1) Text("仅剩 1 次机会；输错后可能需要恢复 PIN。")
        } },
        confirmButton = { TextButton(onClick = {
            val entered = pin.toCharArray()
            pin = ""; confirm = false
            onExecute(entered)
        }) { Text("开始第二次扫描") } },
        dismissButton = { TextButton(onClick = { pin = ""; confirm = false }) { Text("取消") } },
    )
}

internal fun protectedPivActionTitle(action: ProtectedPivAction): String = when (action) {
    ProtectedPivAction.STATUS -> "状态"
    ProtectedPivAction.SLOT -> "槽位与证书"
    ProtectedPivAction.SSH -> "导出 SSH 公钥"
    ProtectedPivAction.VERIFY_PIN -> "验证 PIN"
    ProtectedPivAction.CSR -> "导出 CSR"
    ProtectedPivAction.RAW_SIGN -> "原始文件签名"
    ProtectedPivAction.ECDH -> "验证 ECDH"
    ProtectedPivAction.RSA -> "验证 RSA 解密"
}
