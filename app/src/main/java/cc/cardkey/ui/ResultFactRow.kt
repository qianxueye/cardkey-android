package cc.cardkey.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun ResultFactRow(row: ResultRow) {
    val label = row.labelResource?.let { stringResource(it) } ?: row.label
    val value = row.valueResource?.let { stringResource(it, *row.valueArguments.toTypedArray()) } ?: row.value
    PlexoFactRow(label, value)
}

internal fun protectedPivOperationTitleResource(operation: ManagerOperation): Int? = when (operation) {
    ManagerOperation.PIV_INFO -> cc.cardkey.R.string.protectedPiv_operationStatus
    ManagerOperation.PIV_SLOT -> cc.cardkey.R.string.protectedPiv_operationSlot
    ManagerOperation.PIV_SSH_EXPORT -> cc.cardkey.R.string.protectedPiv_actionSsh
    ManagerOperation.PIV_CSR -> cc.cardkey.R.string.protectedPiv_actionCsr
    ManagerOperation.PIV_SIGN -> cc.cardkey.R.string.protectedPiv_operationSign
    ManagerOperation.PIV_ECDH -> cc.cardkey.R.string.protectedPiv_operationEcdh
    ManagerOperation.PIV_DECRYPT -> cc.cardkey.R.string.protectedPiv_operationRsa
    else -> null
}
