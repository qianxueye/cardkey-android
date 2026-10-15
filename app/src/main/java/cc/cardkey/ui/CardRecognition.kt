package cc.cardkey.ui

enum class CardFamily { FIDO2, U2F, PIV }

// This records an explicit read on a route, not continued presence or physical card identity.
data class CardRecognition(val route: String, val lifecycle: Long, val family: CardFamily)

internal val ManagerOperation.recognitionFamily: CardFamily?
    get() = when (this) {
        ManagerOperation.FIDO_INFO -> CardFamily.FIDO2
        ManagerOperation.U2F_INFO -> CardFamily.U2F
        ManagerOperation.PIV_INFO -> CardFamily.PIV
        else -> null
    }
