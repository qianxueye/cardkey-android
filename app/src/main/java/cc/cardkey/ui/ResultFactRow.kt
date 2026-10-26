package cc.cardkey.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import cc.cardkey.R
import androidx.compose.ui.res.stringResource

internal val LocalFidoModelCatalog = staticCompositionLocalOf<FidoModelCatalog?> { null }

@Composable
internal fun ResultFactRow(row: ResultRow) {
    val label = row.labelResource?.let { stringResource(it) } ?: row.label
    val value = row.valueResource?.let { stringResource(it, *row.valueArguments.toTypedArray()) } ?: row.value
    if (row.fidoModel) {
        FidoModelFactRow(row.value)
    } else PlexoFactRow(label, value)
}

@Composable
internal fun FidoModelFactRow(aaguid: String, catalog: FidoModelCatalog = LocalFidoModelCatalog.current ?: FidoModelCatalog.shared(LocalContext.current.cacheDir)) {
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    key(aaguid, locale, catalog) {
        val result by produceState<FidoModelName?>(catalog.cached(aaguid, locale), aaguid, locale, catalog) {
            value = catalog.lookup(aaguid, locale)
        }
        val value = when (val name = result) {
            is FidoModelName.Known -> name.name
            FidoModelName.Missing -> stringResource(R.string.fido_modelMissing)
            FidoModelName.Failed -> stringResource(R.string.fido_modelFailed)
            null -> stringResource(R.string.fido_modelLoading)
        }
        PlexoFactRow(stringResource(R.string.fido_associatedModel), value)
    }
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

internal fun confirmationOperationTitleResource(operation: ManagerOperation): Int? = when (operation) {
    ManagerOperation.FIDO_DELETE -> cc.cardkey.R.string.confirmTitle_FIDO_DELETE
    ManagerOperation.FIDO_ALWAYS_UV -> cc.cardkey.R.string.confirmTitle_FIDO_ALWAYS_UV
    ManagerOperation.FIDO_MIN_PIN -> cc.cardkey.R.string.confirmTitle_FIDO_MIN_PIN
    ManagerOperation.FIDO_ENTERPRISE -> cc.cardkey.R.string.confirmTitle_FIDO_ENTERPRISE
    ManagerOperation.FIDO_REGISTER -> cc.cardkey.R.string.confirmTitle_FIDO_REGISTER
    ManagerOperation.U2F_REGISTER -> cc.cardkey.R.string.confirmTitle_U2F_REGISTER
    ManagerOperation.FIDO_ASSERT -> cc.cardkey.R.string.confirmTitle_FIDO_ASSERT
    ManagerOperation.FIDO_VERIFY_CREDENTIAL -> cc.cardkey.R.string.confirmTitle_FIDO_VERIFY_CREDENTIAL
    ManagerOperation.FIDO_BLOB_READ -> cc.cardkey.R.string.confirmTitle_FIDO_BLOB_READ
    ManagerOperation.FIDO_BLOB_WRITE -> cc.cardkey.R.string.confirmTitle_FIDO_BLOB_WRITE
    ManagerOperation.FIDO_RESET -> cc.cardkey.R.string.confirmTitle_FIDO_RESET
    ManagerOperation.PIV_ADMIN -> cc.cardkey.R.string.confirmTitle_PIV_ADMIN
    ManagerOperation.PIV_GENERATE -> cc.cardkey.R.string.confirmTitle_PIV_GENERATE
    ManagerOperation.PIV_IMPORT -> cc.cardkey.R.string.confirmTitle_PIV_IMPORT
    ManagerOperation.PIV_ENROLL_IMPORT -> cc.cardkey.R.string.confirmTitle_PIV_ENROLL_IMPORT
    ManagerOperation.PIV_ENROLL_CSR -> cc.cardkey.R.string.confirmTitle_PIV_ENROLL_CSR
    ManagerOperation.PIV_RESET -> cc.cardkey.R.string.confirmTitle_PIV_RESET
    ManagerOperation.PIV_CHANGE_PIN -> cc.cardkey.R.string.confirmTitle_PIV_CHANGE_PIN
    ManagerOperation.PIV_CHANGE_PUK -> cc.cardkey.R.string.confirmTitle_PIV_CHANGE_PUK
    ManagerOperation.PIV_UNBLOCK -> cc.cardkey.R.string.confirmTitle_PIV_UNBLOCK
    ManagerOperation.PIV_CMS_SIGN -> cc.cardkey.R.string.confirmTitle_PIV_CMS_SIGN
    else -> protectedPivOperationTitleResource(operation)
}
