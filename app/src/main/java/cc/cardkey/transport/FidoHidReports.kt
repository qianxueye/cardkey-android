package cc.cardkey.transport

internal data class FidoHidReports(
    val inputBytes: Int = 64,
    val outputBytes: Int = 64,
    val inputReportID: Int = 0,
    val outputReportID: Int = 0,
) {
    init {
        require(inputBytes in 7..1024 && outputBytes in 7..1024)
        require(inputReportID in 0..255 && outputReportID in 0..255)
        require((inputReportID == 0) == (outputReportID == 0))
    }
    val inputNativeBytes: Int get() = inputBytes + if (inputReportID == 0) 0 else 1
    val outputNativeBytes: Int get() = outputBytes + if (outputReportID == 0) 0 else 1
    val inputCapacity: Int get() = capacity(inputBytes)
    val outputCapacity: Int get() = capacity(outputBytes)
    companion object {
        fun capacity(bytes: Int): Int {
            require(bytes in 7..1024)
            return minOf(65535L, (bytes - 7L) + 128L * (bytes - 5L)).toInt()
        }
    }
}

