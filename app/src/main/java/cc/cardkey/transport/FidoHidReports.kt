package cc.cardkey.transport

internal data class FidoHidReports(val inputBytes: Int = 64, val outputBytes: Int = 64) {
    init { require(inputBytes in 7..1024 && outputBytes in 7..1024) }
    val inputCapacity: Int get() = capacity(inputBytes)
    val outputCapacity: Int get() = capacity(outputBytes)
    companion object {
        fun capacity(bytes: Int): Int {
            require(bytes in 7..1024)
            return minOf(65535L, (bytes - 7L) + 128L * (bytes - 5L)).toInt()
        }
    }
}

