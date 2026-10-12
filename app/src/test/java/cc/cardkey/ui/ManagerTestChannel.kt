package cc.cardkey.ui

import cc.cardkey.protocol.*
import cc.cardkey.protocol.fido.*

internal class ManagerTestChannel(override val wire: CardWire, private val respond: (Int, Cbor.Map?) -> ByteArray) : CardChannel {
    private val apdu = FidoChannel(respond)
    private val typedCommands = mutableListOf<Pair<Int, Cbor.Map?>>()
    val commands get() = if (wire == CardWire.APDU) apdu.commands else typedCommands
    override val medium = Medium.CONTACT
    override val maxCommandBytes get() = if (wire == CardWire.APDU) 261 else 7609
    override val supportsCtap2 get() = wire == CardWire.CTAP
    var closed = false
        private set

    override fun transmit(command: ByteArray): ByteArray {
        check(wire == CardWire.APDU && !closed)
        return apdu.transmit(command)
    }

    override fun exchangeCtap2(command: ByteArray): ByteArray {
        check(wire == CardWire.CTAP && !closed)
        val code = command[0].toInt() and 255
        val parameters = if (command.size == 1) null else CborCodec.decode(command.copyOfRange(1, command.size)).map()
        typedCommands += code to parameters
        return respond(code, parameters)
    }

    override fun close() { closed = true; apdu.close() }
}
