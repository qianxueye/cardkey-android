package cc.cardkey.ui

class PendingOperation(input: OperationInput) : AutoCloseable {
    val operation = input.operation
    val messages = ConfirmationPolicy.messages(input)
    private var pending: OperationInput? = input

    fun dispatch(send: (OperationInput) -> Unit): Boolean {
        val input = pending ?: return false
        pending = null
        try { send(input) } catch (error: Exception) { input.close(); throw error }
        return true
    }

    override fun close() {
        pending?.close()
        pending = null
    }
}
