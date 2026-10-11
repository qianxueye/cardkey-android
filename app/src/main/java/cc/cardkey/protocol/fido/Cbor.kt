package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardFailure
import cc.cardkey.protocol.FailureCode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

sealed interface Cbor {
    data class Integer(val value: Long) : Cbor
    class Bytes(val value: ByteArray) : Cbor {
        override fun equals(other: Any?) = other is Bytes && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
    data class Text(val value: String) : Cbor
    data class Array(val value: List<Cbor>) : Cbor
    data class Map(val value: kotlin.collections.Map<Cbor, Cbor>) : Cbor {
        operator fun get(key: Int) = value[Integer(key.toLong())]
        operator fun get(key: String) = value[Text(key)]
    }
    data class Bool(val value: Boolean) : Cbor
    data object Null : Cbor
}

fun cborMap(vararg pairs: Pair<Int, Cbor?>) = Cbor.Map(pairs.filter { it.second != null }
    .associate { Cbor.Integer(it.first.toLong()) to it.second!! })
fun textMap(vararg pairs: Pair<String, Cbor?>) = Cbor.Map(pairs.filter { it.second != null }
    .associate { Cbor.Text(it.first) to it.second!! })
internal fun Int.cbor() = Cbor.Integer(toLong())
internal fun ByteArray.cbor() = Cbor.Bytes(this)
internal fun String.cbor() = Cbor.Text(this)
internal fun Boolean.cbor() = Cbor.Bool(this)
internal fun Cbor?.map() = this as? Cbor.Map ?: malformed()
internal fun Cbor?.bytes() = (this as? Cbor.Bytes)?.value ?: malformed()
internal fun Cbor?.text() = (this as? Cbor.Text)?.value ?: malformed()
internal fun Cbor?.number() = (this as? Cbor.Integer)?.value ?: malformed()
internal fun Cbor?.bool() = (this as? Cbor.Bool)?.value ?: malformed()
internal fun Cbor?.array() = (this as? Cbor.Array)?.value ?: malformed()
internal fun malformed(): Nothing = throw CardFailure(FailureCode.MALFORMED_RESPONSE)

object CborCodec {
    const val MAX_BYTES = 65535
    private const val MAX_ITEMS = 4096
    private const val MAX_DEPTH = 16

    fun encode(value: Cbor): ByteArray {
        val out = ByteArrayOutputStream()
        var items = 0
        fun head(major: Int, n: Long) {
            require(n >= 0)
            val count = when { n < 24 -> 0; n <= 255 -> 1; n <= 65535 -> 2; n <= 0xffffffffL -> 4; else -> 8 }
            out.write((major shl 5) or when (count) { 0 -> n.toInt(); 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 })
            for (i in count - 1 downTo 0) out.write((n ushr (i * 8)).toInt() and 255)
        }
        fun put(v: Cbor, depth: Int) {
            require(depth <= MAX_DEPTH && ++items <= MAX_ITEMS)
            when (v) {
                is Cbor.Integer -> head(if (v.value >= 0) 0 else 1, if (v.value >= 0) v.value else -(v.value + 1))
                is Cbor.Bytes -> { require(v.value.size <= MAX_BYTES); head(2, v.value.size.toLong()); out.write(v.value) }
                is Cbor.Text -> {
                    val b = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(v.value)) }
                    catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Invalid Unicode") }
                    val bytes = ByteArray(b.remaining()).also { b.get(it) }
                    head(3, bytes.size.toLong()); out.write(bytes)
                }
                is Cbor.Array -> { head(4, v.value.size.toLong()); v.value.forEach { put(it, depth + 1) } }
                is Cbor.Map -> {
                    require(v.value.keys.all { it is Cbor.Integer || it is Cbor.Text || it is Cbor.Bytes })
                    head(5, v.value.size.toLong())
                    val sorted = v.value.entries.map { encode(it.key) to it.value }.sortedWith { a, b -> compare(a.first, b.first) }
                    items += sorted.size
                    require(items <= MAX_ITEMS)
                    sorted.forEach { (key, item) -> out.write(key); put(item, depth + 1) }
                }
                is Cbor.Bool -> out.write(if (v.value) 0xf5 else 0xf4)
                Cbor.Null -> out.write(0xf6)
            }
            require(out.size() <= MAX_BYTES)
        }
        put(value, 0)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Cbor {
        val (value, consumed) = decodePrefix(bytes)
        if (consumed != bytes.size) malformed()
        return value
    }

    fun decodePrefix(bytes: ByteArray): Pair<Cbor, Int> {
        if (bytes.size > MAX_BYTES) malformed()
        var pos = 0
        var items = 0
        fun byte(): Int { if (pos >= bytes.size) malformed(); return bytes[pos++].toInt() and 255 }
        fun size(n: Long): Int { if (n > MAX_BYTES || n < 0) malformed(); return n.toInt() }
        fun read(depth: Int): Cbor {
            if (depth > MAX_DEPTH || ++items > MAX_ITEMS) malformed()
            val first = byte(); val major = first ushr 5; val additional = first and 31
            if (major == 7) return when (additional) { 20 -> Cbor.Bool(false); 21 -> Cbor.Bool(true); 22 -> Cbor.Null; else -> malformed() }
            val n = when {
                additional < 24 -> additional.toLong()
                additional in 24..27 -> {
                    val count = 1 shl (additional - 24)
                    var v = 0L
                    repeat(count) { val b = byte(); if (it == 0 && count == 8 && b >= 128) malformed(); v = (v shl 8) or b.toLong() }
                    val min = when(count) { 1 -> 24L; 2 -> 256L; 4 -> 65536L; else -> 0x100000000L }
                    if (v < min) malformed()
                    v
                }
                else -> malformed()
            }
            return when (major) {
                0 -> Cbor.Integer(n)
                1 -> Cbor.Integer(-1 - n)
                2, 3 -> {
                    val length = size(n)
                    if (length > bytes.size - pos) malformed()
                    val data = bytes.copyOfRange(pos, pos + length); pos += length
                    if (major == 2) Cbor.Bytes(data) else {
                        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString() }
                        catch (_: java.nio.charset.CharacterCodingException) { malformed() }
                        Cbor.Text(text)
                    }
                }
                4 -> { if (n > MAX_ITEMS) malformed(); Cbor.Array(List(size(n)) { read(depth + 1) }) }
                5 -> {
                    if (n > MAX_ITEMS) malformed()
                    val map = linkedMapOf<Cbor, Cbor>(); var previous: ByteArray? = null
                    repeat(size(n)) {
                        val start = pos; val key = read(depth + 1)
                        if (key !is Cbor.Integer && key !is Cbor.Text && key !is Cbor.Bytes) malformed()
                        val encoded = bytes.copyOfRange(start, pos)
                        if (previous != null && compare(previous, encoded) >= 0) malformed()
                        previous = encoded
                        map[key] = read(depth + 1)
                    }
                    Cbor.Map(map)
                }
                else -> malformed()
            }
        }
        return read(0) to pos
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val major = (a[0].toInt() and 255 ushr 5).compareTo(b[0].toInt() and 255 ushr 5)
        if (major != 0) return major
        if (a.size != b.size) return a.size.compareTo(b.size)
        for (i in a.indices) { val c = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255); if (c != 0) return c }
        return 0
    }
}
