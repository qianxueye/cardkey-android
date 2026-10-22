package cc.cardkey.ui

internal fun protectedPivDisplayText(value: String): String = buildString {
    var offset = 0
    var count = 0
    while (offset < value.length && count < 255) {
        val point = value.codePointAt(offset)
        offset += Character.charCount(point)
        when (Character.getType(point)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt() -> continue
        }
        appendCodePoint(point)
        count++
    }
}
