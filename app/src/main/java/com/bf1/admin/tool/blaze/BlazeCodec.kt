package com.bf1.admin.tool.blaze

import java.io.ByteArrayOutputStream

/**
 * Blaze TDF 二进制编解码器，逐字节对应 CardTool.js 中的 Blaze 类（.references/CardTool-预热）。
 *
 * 数据模型与 JS 版一致，解码结果为 [Map]：
 * - Integer → [Long]
 * - String → [String]
 * - Blob → hex [String]（解码时去掉末尾 0x00 终止符；编码时调用方需自带终止符，与 JS 一致）
 * - List → [List]<Any?>
 * - Map → [Map]<String, Any?>
 * - Double/Tripple → [List]<Long>
 * - Float → [Float]
 *
 * 字段 key 与 JS 一致：列表/映射/联合体类型会带上子类型数字（如 "PLDL 43"、"PLYA 511"）。
 *
 * 负数整数：-64 及更小可正确往返；-1..-63 在 CardTool.js 中本身编码损坏，此处忠实复刻。
 */
object BlazeCodec {

    // ── 包类型（Header 第 14 字节）──
    const val TYPE_SEND_COMMAND = 0x00
    const val TYPE_RESULT = 0x20
    const val TYPE_RECEIVE_MESSAGE = 0x40
    const val TYPE_ERROR = 0x60
    const val TYPE_SEND_KEEPALIVE = 0x80
    const val TYPE_RECEIVE_KEEPALIVE = 0xa0

    const val HEADER_SIZE = 16
    private const val MAX_LIST_ITEMS = 1_000_000L

    // 与 JS `tags` Map 对应的 tag 编码缓存（纯优化，编码结果确定）。
    private val tagCache = HashMap<String, ByteArray>()

    // ═══════════════════════════════════════════════════
    // 编码
    // ═══════════════════════════════════════════════════

    fun encode(method: String, type: Int, id: Int, data: Map<String, Any?>): ByteArray {
        val payload = ByteArrayOutputStream()
        // 顶层 struct 不带结束标记，与 JS `writeStruct(packet.data, false)` 一致
        writeStruct(payload, data, writeEndMarker = false)
        val payloadBytes = payload.toByteArray()
        val header = ByteArray(HEADER_SIZE)
        val (componentId, commandId) = resolveMethodIds(method)
        header[6] = (componentId shr 8).toByte()
        header[7] = componentId.toByte()
        header[8] = (commandId shr 8).toByte()
        header[9] = commandId.toByte()
        header[11] = (id shr 8).toByte()
        header[12] = id.toByte()
        header[13] = type.toByte()
        if (type == TYPE_ERROR) {
            // 错误包长度写在 offset 4（2 字节），与 JS 一致
            header[4] = (payloadBytes.size shr 8).toByte()
            header[5] = payloadBytes.size.toByte()
        } else {
            header[0] = (payloadBytes.size shr 24).toByte()
            header[1] = (payloadBytes.size shr 16).toByte()
            header[2] = (payloadBytes.size shr 8).toByte()
            header[3] = payloadBytes.size.toByte()
        }
        return header + payloadBytes
    }

    /** 从 16 字节 Header 读取 payload 长度（错误包长度在 offset 4）。 */
    fun payloadLength(header: ByteArray): Int = readInt32BE(header, 0) + readInt16BE(header, 4)

    private fun writeStruct(out: ByteArrayOutputStream, obj: Map<String, Any?>, writeEndMarker: Boolean) {
        for ((key, value) in obj) {
            val tag = key.substring(0, 4)
            val typeHex = key.substring(5)
            writeTag(out, tag)
            out.write(Character.digit(typeHex[0], 16))
            writeBlock(out, typeHex, value, key)
        }
        if (writeEndMarker) out.write(0)
    }

    private fun writeBlock(out: ByteArrayOutputStream, typeHex: String, value: Any?, key: String?) {
        when (typeHex[0]) {
            '0' -> writeInteger(out, value as Number)
            '1' -> writeString(out, value as String)
            '2' -> writeBlob(out, value as String)
            '3' -> writeStruct(out, value as Map<String, Any?>, true)
            '4' -> writeList(out, value as List<*>, requireNotNull(key) { "List field requires key" })
            '5' -> writeMap(out, value as Map<String, Any?>, requireNotNull(key) { "Map field requires key" })
            '6' -> writeUnion(out, value as Map<String, Any?>, requireNotNull(key) { "Union field requires key" })
            '7' -> writeIntList(out, value as List<*>)
            '8' -> writeObjectType(out, value as List<*>)
            '9' -> writeObjectId(out, value as List<*>)
            'a' -> writeFloat(out, (value as Number).toFloat())
            else -> throw IllegalArgumentException("Unknown Blaze type: $typeHex")
        }
    }

    private fun writeList(out: ByteArrayOutputStream, list: List<*>, key: String) {
        val itemTypeChar = key[6]
        out.write(Character.digit(itemTypeChar, 16))
        writeInteger(out, list.size)
        for (item in list) writeBlock(out, itemTypeChar.toString(), item, null)
    }

    private fun writeMap(out: ByteArrayOutputStream, map: Map<String, Any?>, key: String) {
        val keyTypeChar = key[6]
        val valueTypeChar = key[7]
        out.write(Character.digit(keyTypeChar, 16))
        out.write(Character.digit(valueTypeChar, 16))
        writeInteger(out, map.size)
        for ((k, v) in map) {
            writeBlock(out, keyTypeChar.toString(), k, null)
            writeBlock(out, valueTypeChar.toString(), v, null)
        }
    }

    private fun writeUnion(out: ByteArrayOutputStream, value: Map<String, Any?>, key: String) {
        if (key.length > 6) {
            out.write(Character.digit(key[6], 16))
            writeStruct(out, value, false)
        } else {
            out.write(0x7f)
        }
    }

    private fun writeIntList(out: ByteArrayOutputStream, list: List<*>) {
        writeInteger(out, list.size)
        for (item in list) writeInteger(out, item as Number)
    }

    private fun writeObjectType(out: ByteArrayOutputStream, obj: List<*>) {
        writeInteger(out, obj[0] as Number)
        writeInteger(out, obj[1] as Number)
    }

    private fun writeObjectId(out: ByteArrayOutputStream, obj: List<*>) {
        writeInteger(out, obj[0] as Number)
        writeInteger(out, obj[1] as Number)
        writeInteger(out, obj[2] as Number)
    }

    private fun writeFloat(out: ByteArrayOutputStream, value: Float) {
        val bits = value.toRawBits()
        out.write((bits shr 24) and 0xFF)
        out.write((bits shr 16) and 0xFF)
        out.write((bits shr 8) and 0xFF)
        out.write(bits and 0xFF)
    }

    private fun writeInteger(out: ByteArrayOutputStream, input: Number) {
        // 与 JS writeInteger 完全一致（含负数分支的语义）
        var negative = false
        var value = input.toLong()
        if (value < 0) {
            negative = true
            value = -value
        }
        if (negative) value -= 64
        val bytes = ArrayList<Long>()
        bytes.add(value % 64 + 128)
        value = Math.floorDiv(value, 64)
        while (value > 0) {
            bytes.add(value % 128 + 128)
            value = Math.floorDiv(value, 128)
        }
        if (negative) bytes[0] = bytes[0] + 64
        bytes[bytes.size - 1] = bytes[bytes.size - 1] - 128
        for (b in bytes) out.write((b and 0xFF).toInt())
    }

    private fun writeString(out: ByteArrayOutputStream, text: String) {
        if (text.isEmpty()) {
            out.write(0x01)
            out.write(0x00)
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInteger(out, bytes.size.toLong() + 1)
        out.write(bytes)
        out.write(0)
    }

    private fun writeBlob(out: ByteArrayOutputStream, blobHex: String) {
        val len = blobHex.length / 2
        writeInteger(out, len.toLong())
        for (i in 0 until len) {
            out.write(Integer.parseInt(blobHex.substring(i * 2, i * 2 + 2), 16))
        }
    }

    private fun writeTag(out: ByteArrayOutputStream, tag: String) {
        tagCache[tag]?.let { bytes ->
            out.write(bytes)
            return
        }
        var encoded = 0L
        for (i in 0 until 4) {
            val c = tag[i].code - 0x20
            encoded += c.toLong() shl (18 - 6 * i)
        }
        val bytes = byteArrayOf(
            ((encoded shr 16) and 0xFF).toByte(),
            ((encoded shr 8) and 0xFF).toByte(),
            (encoded and 0xFF).toByte()
        )
        tagCache[tag] = bytes
        out.write(bytes)
    }

    // ═══════════════════════════════════════════════════
    // 解码
    // ═══════════════════════════════════════════════════

    fun decode(buffer: ByteArray): BlazePacket {
        val payloadLength = readInt32BE(buffer, 0) + readInt16BE(buffer, 4)
        val packetType = buffer[13].toInt() and 0xFF
        val componentId = readInt16BE(buffer, 6)
        val commandId = readInt16BE(buffer, 8)
        val packetId = readInt16BE(buffer, 11)

        val method = resolveMethod(componentId, commandId, packetType)
        val (data, _) = parseStruct(buffer, HEADER_SIZE)

        val errc = data["ERRC 0"] as? Long
        // 与 CardTool.js 一致：ERRC 为 0 表示无错误（成功的响应也带 ERRC 字段），仅非 0 才是错误
        return if (errc != null && errc != 0L) {
            val errorComponentId = (errc and 0xFFFFL).toInt()
            var errorCode = (errc shr 16).toInt()
            if (errorCode >= 16384) errorCode -= 16384
            val compName = BlazeProtocol.components[errorComponentId] ?: errorComponentId.toString()
            val def = BlazeProtocol.errors[compName]?.get(errorCode)
            BlazePacket(
                method = method,
                type = packetType,
                id = packetId,
                length = payloadLength,
                error = BlazeError(compName, def?.name ?: errorCode.toString(), def?.description, errc),
                errc = errc,
                data = null,
                rawBytes = buffer
            )
        } else {
            BlazePacket(method, packetType, packetId, payloadLength, null, null, data)
        }
    }

    /** 类型持有者：与 JS `field.type` 突变一致，列表/映射/联合体追加子类型 hex 后拼进 key。 */
    private class FieldTypeHolder(val type: Int) {
        val display = StringBuilder(type.toString(16))
    }

    private fun parseStruct(buffer: ByteArray, start: Int): Pair<LinkedHashMap<String, Any?>, Int> {
        var offset = start
        val obj = LinkedHashMap<String, Any?>()
        while (offset < buffer.size && (buffer[offset].toInt() and 0xFF) != 0) {
            val tag = decodeTag(buffer, offset)
            offset += 3
            val type = buffer[offset].toInt() and 0xFF
            offset += 1
            val holder = FieldTypeHolder(type)
            val parsed = parseBlock(buffer, offset, holder)
            offset = parsed.second
            obj[tag.padEnd(4, ' ') + " " + holder.display] = parsed.first
        }
        if (offset < buffer.size) offset += 1
        return Pair(obj, offset)
    }

    private fun parseBlock(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<Any?, Int> = when (holder.type) {
        0x00 -> parseInteger(buffer, start)
        0x01 -> parseString(buffer, start)
        0x02 -> parseBlob(buffer, start)
        0x03 -> parseStruct(buffer, start)
        0x04 -> parseList(buffer, start, holder)
        0x05 -> parseMap(buffer, start, holder)
        0x06 -> parseUnion(buffer, start, holder)
        0x07 -> parseIntList(buffer, start, holder)
        0x08 -> parseObjectType(buffer, start, holder)
        0x09 -> parseObjectId(buffer, start, holder)
        0x0a -> parseFloat(buffer, start, holder)
        else -> throw IllegalArgumentException("Unknown Blaze type: ${holder.type}")
    }

    private fun parseList(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<MutableList<Any?>, Int> {
        var offset = start
        val itemType = buffer[offset].toInt() and 0xFF
        offset += 1
        holder.display.append(itemType.toString(16))
        val size = parseInteger(buffer, offset)
        offset = size.second
        // 与 JS 一致：struct 列表的元素类型字节后若紧跟 0x02 标记则跳过
        if (itemType == 0x03 && offset < buffer.size && (buffer[offset].toInt() and 0xFF) == 0x02) {
            holder.display.append("2")
            offset += 1
        }
        val list = ArrayList<Any?>()
        val n = size.first.coerceIn(0, MAX_LIST_ITEMS).toInt()
        for (i in 0 until n) {
            val parsed = parseBlock(buffer, offset, FieldTypeHolder(itemType))
            offset = parsed.second
            list.add(parsed.first)
        }
        return Pair(list, offset)
    }

    private fun parseMap(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<LinkedHashMap<String, Any?>, Int> {
        var offset = start
        val keyType = buffer[offset].toInt() and 0xFF
        offset += 1
        val valueType = buffer[offset].toInt() and 0xFF
        offset += 1
        holder.display.append(keyType.toString(16)).append(valueType.toString(16))
        val size = parseInteger(buffer, offset)
        offset = size.second
        val map = LinkedHashMap<String, Any?>()
        val n = size.first.coerceIn(0, MAX_LIST_ITEMS).toInt()
        for (i in 0 until n) {
            val k = parseBlock(buffer, offset, FieldTypeHolder(keyType))
            offset = k.second
            val v = parseBlock(buffer, offset, FieldTypeHolder(valueType))
            offset = v.second
            map[k.first.toString()] = v.first
        }
        return Pair(map, offset)
    }

    private fun parseUnion(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<LinkedHashMap<String, Any?>, Int> {
        var offset = start
        val activeMemberIndex = buffer[offset].toInt() and 0xFF
        offset += 1
        val unionValue = LinkedHashMap<String, Any?>()
        if (activeMemberIndex == 0x7f) return Pair(unionValue, offset)
        holder.display.append(activeMemberIndex.toString(16))
        val tag = decodeTag(buffer, offset)
        offset += 3
        val type = buffer[offset].toInt() and 0xFF
        offset += 1
        val memberHolder = FieldTypeHolder(type)
        val parsed = parseBlock(buffer, offset, memberHolder)
        offset = parsed.second
        unionValue[tag.padEnd(4, ' ') + " " + memberHolder.display] = parsed.first
        return Pair(unionValue, offset)
    }

    private fun parseIntList(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<ArrayList<Long>, Int> {
        var offset = start
        val size = parseInteger(buffer, offset)
        offset = size.second
        val list = ArrayList<Long>()
        val n = size.first.coerceIn(0, MAX_LIST_ITEMS).toInt()
        for (i in 0 until n) {
            val v = parseInteger(buffer, offset)
            offset = v.second
            list.add(v.first)
        }
        return Pair(list, offset)
    }

    private fun parseObjectType(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<ArrayList<Long>, Int> {
        var offset = start
        val a = parseInteger(buffer, offset)
        offset = a.second
        val b = parseInteger(buffer, offset)
        offset = b.second
        return Pair(arrayListOf(a.first, b.first), offset)
    }

    private fun parseObjectId(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<ArrayList<Long>, Int> {
        var offset = start
        val a = parseInteger(buffer, offset)
        offset = a.second
        val b = parseInteger(buffer, offset)
        offset = b.second
        val c = parseInteger(buffer, offset)
        offset = c.second
        return Pair(arrayListOf(a.first, b.first, c.first), offset)
    }

    private fun parseFloat(buffer: ByteArray, start: Int, holder: FieldTypeHolder): Pair<Float, Int> {
        val bits = readInt32BE(buffer, start)
        return Pair(Float.fromBits(bits), start + 4)
    }

    private fun parseInteger(buffer: ByteArray, start: Int): Pair<Long, Int> {
        var offset = start
        var shiftIndex = 1
        var value = buffer[offset].toLong() and 0xFF
        offset += 1
        val negative = (value and 0x40L) != 0L
        if ((value and 0x80L) != 0L) {
            value = value and 0x7fL
            do {
                val b = if (offset < buffer.size) (buffer[offset].toLong() and 0x7fL) else 0L
                value += b * (1L shl (7 * shiftIndex - 1))
                shiftIndex++
                val cont = offset < buffer.size && (buffer[offset].toLong() and 0xFFL) > 0x7fL
                offset++
            } while (cont)
        }
        return Pair(if (negative) -value else value, offset)
    }

    private fun parseString(buffer: ByteArray, start: Int): Pair<String, Int> {
        val length = parseInteger(buffer, start)
        var offset = length.second
        val end = (offset + length.first).toInt().coerceAtMost(buffer.size)
        val contentEnd = (end - 1).coerceAtLeast(offset)
        val str = String(buffer, offset, contentEnd - offset, Charsets.UTF_8)
        return Pair(str, end)
    }

    private fun parseBlob(buffer: ByteArray, start: Int): Pair<String, Int> {
        val length = parseInteger(buffer, start)
        var offset = length.second
        val end = (offset + length.first).toInt().coerceAtMost(buffer.size)
        val contentEnd = (end - 1).coerceAtLeast(offset)
        val sb = StringBuilder()
        for (i in offset until contentEnd) sb.append("%02x".format(buffer[i].toInt() and 0xFF))
        return Pair(sb.toString(), end)
    }

    private fun decodeTag(buffer: ByteArray, offset: Int): String {
        val n = ((buffer[offset].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            (buffer[offset + 2].toInt() and 0xFF)
        val chars = CharArray(4)
        for (i in 0 until 4) {
            chars[i] = (((n shr (18 - 6 * i)) and 0x3f) + 0x20).toChar()
        }
        return String(chars)
    }

    // ═══════════════════════════════════════════════════
    // 协议表查询
    // ═══════════════════════════════════════════════════

    private fun resolveMethod(componentId: Int, commandId: Int, packetType: Int): String {
        if (packetType == TYPE_SEND_KEEPALIVE || packetType == TYPE_RECEIVE_KEEPALIVE) return "KeepAlive"
        val compName = BlazeProtocol.components[componentId]
        val category = if (packetType == TYPE_RECEIVE_MESSAGE) "Message" else "Command"
        val cmdName = compName?.let { BlazeProtocol.commands[it]?.get(category)?.get(commandId) }
        return "${compName ?: componentId}.${cmdName ?: commandId}"
    }

    private fun resolveMethodIds(method: String): Pair<Int, Int> {
        val dot = method.indexOf('.')
        if (dot <= 0) return 0 to 0
        val compName = method.substring(0, dot)
        val cmdName = method.substring(dot + 1)
        val compId = BlazeProtocol.components.entries.firstOrNull { it.value == compName }?.key
            ?: compName.toIntOrNull()
            ?: 0
        val cmdId = if (compId != 0) {
            BlazeProtocol.commands[compName]?.get("Command")?.entries?.firstOrNull { it.value == cmdName }?.key
                ?: cmdName.toIntOrNull()
                ?: 0
        } else {
            0
        }
        return compId to cmdId
    }

    private fun readInt16BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readInt32BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
