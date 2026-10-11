package cc.cardkey.protocol.fido

import cc.cardkey.protocol.CardChannel
import cc.cardkey.protocol.Medium
import java.io.ByteArrayOutputStream

internal class FidoChannel(private val respond: (Int, Cbor.Map?) -> ByteArray) : CardChannel {
    override val medium = Medium.CONTACT
    override val maxCommandBytes = 261
    val commands = mutableListOf<Pair<Int, Cbor.Map?>>()
    var closed = false
    var selectCount = 0
    private val pending = ByteArrayOutputStream()
    override fun transmit(command: ByteArray): ByteArray {
        check(!closed)
        if (command[1].toInt() and 255 == 0xa4) {
            selectCount++
            return "FIDO_2_0".toByteArray() + byteArrayOf(0x90.toByte(), 0)
        }
        val length = command[4].toInt() and 255
        pending.write(command, 5, length)
        if (command[0].toInt() and 0x10 != 0) return byteArrayOf(0x90.toByte(), 0)
        val data = pending.toByteArray(); pending.reset()
        val code = data[0].toInt() and 255
        val parameters = if (data.size == 1) null else CborCodec.decode(data.copyOfRange(1, data.size)).map()
        commands += code to parameters
        return respond(code, parameters) + byteArrayOf(0x90.toByte(), 0)
    }
    override fun close() { closed = true }
}
internal fun success(map: Cbor.Map? = null) = byteArrayOf(0) + (map?.let(CborCodec::encode) ?: byteArrayOf())
internal fun info(options: Map<String, Boolean> = emptyMap(), protocols: List<Int> = listOf(2, 1),
    extensions: List<String> = emptyList(), extra: Cbor.Map = cborMap()): Cbor.Map = Cbor.Map(cborMap(
    1 to Cbor.Array(listOf("FIDO_2_1".cbor())), 2 to Cbor.Array(extensions.map { it.cbor() }),
    3 to ByteArray(16).cbor(), 4 to Cbor.Map(options.mapKeys { it.key.cbor() }.mapValues { it.value.cbor() }),
    6 to Cbor.Array(protocols.map { it.cbor() }), 13 to 4.cbor()).value + extra.value)
