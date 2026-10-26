package cc.cardkey.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import cc.cardkey.enrollment.EnrollmentState
import cc.cardkey.protectedpiv.ProtectedPivState
import cc.cardkey.protocol.*
import cc.cardkey.signing.SigningFileState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w800dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CopyPruningTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(content: @Composable () -> Unit, check: () -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val config = Configuration(host.get().resources.configuration).apply { setLocale(Locale.US) }
        val context = host.get().createConfigurationContext(config)
        host.get().setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                MaterialTheme { Box(Modifier.requiredSize(800.dp, 1000.dp)) { content() } }
            }
        }
        try { check() } finally { host.pause().stop().destroy() }
    }

    @Test fun actualConfirmationCallersKeepChineseOperationNamesAndHaveLocalizedTitles() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val chinese = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }).resources
        for (operation in ManagerOperation.entries) {
            for (enabled in listOf(false, true)) {
                val input = OperationInput(operation, enabled = enabled,
                    makeExtensions = cc.cardkey.protocol.fido.MakeExtensions(largeBlobKey = true),
                    assertionExtensions = cc.cardkey.protocol.fido.AssertionExtensions(largeBlobKey = true))
                try {
                    if (ConfirmationPolicy.messages(input).isNotEmpty() || operation == ManagerOperation.PIV_ENROLL_CSR ||
                        ConfirmationPolicy.pivVerificationReference(operation) != null) {
                        val title = confirmationOperationTitleResource(operation)
                        assertNotNull(operation.name, title)
                        assertEquals(operation.name, operation.title, chinese.getString(requireNotNull(title)))
                    }
                } finally { input.close() }
            }
        }
    }

    @Test fun protectedPanelOmitsScopeCaveatsButRetainsRecoveryInstructions() {
        render({ Column(Modifier.verticalScroll(rememberScrollState())) {
            ProtectedPivPanel(ProtectedPivState(profileReady = true), false, true, 0, {}, {}, { _, _, _ -> }, {}, true)
        } }) {
            compose.onNodeWithText("This certificate supplies the enrolled public key and identifier.", substring = true).assertDoesNotExist()
            compose.onNodeWithText("The configuration is ready.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Import the original card configuration again, then read its protected status. Do not repeat the original operation before reading the status.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("protected-piv-pin").assertDoesNotExist()
        }
    }

    @Test fun managerConfirmationLocalizesTitleAndPreservesBoundCredential() {
        val inventory = FidoInventory().apply { seedInventory(this, InventoryCard()); action(ManagerOperation.FIDO_DELETE) }
        var submitted: OperationInput? = null
        try {
            render({
                val state by inventory.state.collectAsState()
                ManagerScreen(ManagerState(recognition = CardRecognition("nfc", 1, CardFamily.FIDO2)), 0,
                    {}, {}, { submitted = it }, {}, {}, null, {}, {}, {}, {}, EnrollmentState(), { _, _ -> }, {},
                    { it.close() }, { token, _ -> token.fill('\u0000') }, {}, TlsQualificationState(), {}, {}, { it.close() },
                    SigningFileState(), { _, _ -> }, {}, state, { _, _ -> }, { _, _ -> }, {}, {},
                    protectedPiv = ProtectedPivState(), onProtectedPivDocument = {}, onProtectedPivClear = {},
                    onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
            }) {
                compose.onNode(hasText("FIDO PIN") and hasSetTextAction()).performScrollTo().performTextInput("1234")
                compose.onNodeWithTag("manager-run").performClick()
                compose.onNodeWithText("Delete passkey").assertIsDisplayed()
                compose.onNodeWithText("网站 · login.example.test").assertIsDisplayed()
                compose.onNodeWithText("账户 · alice").assertIsDisplayed()
                assertNull(submitted)
                compose.onNodeWithText("删除所选凭据后，无法再用它登录已绑定的账户。此操作不可撤销。确认删除？").assertIsDisplayed()
                compose.onNodeWithText("确认").performClick()
                assertEquals(ManagerOperation.FIDO_DELETE, submitted!!.operation)
                assertEquals(inventory.state.value.credential!!.idHex, submitted.inventorySelection!!.idHex)
            }
        } finally { submitted?.close(); inventory.retire() }
    }

    @Test fun enrollmentConfirmationLocalizesTitleBeforeDispatch() {
        var submitted: OperationInput? = null
        try {
            render({ Column(Modifier.verticalScroll(rememberScrollState())) {
                EnrollmentPanel(EnrollmentState(address = "https://ca.example.test", cn = "Synthetic", hasRoot = true),
                    false, true, 0, { _, _ -> }, {}, { submitted = it }, { token, _ -> token.fill('\u0000') }, {}, {}, {}, {}, 1000.dp)
            } }) {
                compose.onNodeWithTag("enrollment-pin").performScrollTo().performTextInput("123456")
                compose.onNodeWithTag("enrollment-create-csr").performScrollTo().performClick()
                compose.onNodeWithText("Create CSR").assertIsDisplayed()
                assertNull(submitted)
                compose.onNodeWithText("确认").performClick()
                assertEquals(ManagerOperation.PIV_ENROLL_CSR, submitted!!.operation)
                assertArrayEquals("123456".toCharArray(), submitted.pin)
            }
        } finally { submitted?.close() }
    }
}
