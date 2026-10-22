package cc.cardkey.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
class ProtectedPivResultHeaderTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(state: ManagerState, check: () -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val config = Configuration(host.get().resources.configuration).apply { setLocale(Locale.US) }
        val context = host.get().createConfigurationContext(config)
        host.get().setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                MaterialTheme {
                    Box(Modifier.requiredSize(800.dp, 1000.dp)) {
                        ManagerScreen(state, 0, {}, {}, { it.close() }, {}, {}, null, {}, {}, {}, {},
                            EnrollmentState(), { _, _ -> }, {}, { it.close() }, { token, _ -> token.fill('\u0000') }, {},
                            TlsQualificationState(), {}, {}, { it.close() }, SigningFileState(), { _, _ -> }, {},
                            InventoryState(), { _, _ -> }, { _, _ -> }, {}, {}, protectedPiv = ProtectedPivState(),
                            onProtectedPivDocument = {}, onProtectedPivClear = {},
                            onProtectedPivRecognize = { _, _, _ -> }, onProtectedPivExecute = { it.fill('\u0000') })
                    }
                }
            }
        }
        try {
            compose.onNode(hasText("通行密钥") and hasClickAction()).performClick()
            check()
        } finally { host.pause().stop().destroy() }
    }

    @Test fun failedResultKeepsEnglishTitleAfterProtectedWorkflowRetires() {
        render(ManagerState(operation = ManagerOperation.PIV_INFO,
            result = OperationResult.Failed(FailureCode.TRANSPORT_FAILURE, Effect.UNKNOWN,
                dispatch = Dispatch.RESPONSE_UNAVAILABLE))) {
            compose.onNodeWithText("View PIV status").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("The command was sent, but no result was received. Changes may have taken effect. Read the card status before deciding what to do next.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("The card connection failed.").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun preparedPublicKeyUsesNeutralOutputLabelBeforeAnySave() {
        render(ManagerState(operation = ManagerOperation.PIV_SSH_EXPORT,
            result = OperationResult.Completed(ManagerReport(emptyList(), byteArrayOf(1, 2),
                "piv-9a-ssh.pub", sshPublicKey = true), Effect.NONE))) {
            compose.onNodeWithText("Output file").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("piv-9a-ssh.pub").assertIsDisplayed()
            compose.onNodeWithText("Exported file").assertDoesNotExist()
            compose.onNodeWithText("The SSH public key was exported.").assertDoesNotExist()
        }
    }
}
