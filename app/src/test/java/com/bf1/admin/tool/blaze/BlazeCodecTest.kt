package com.bf1.admin.tool.blaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlazeCodec 与 CardTool.js 参考实现（.tools/blaze-extract/cardtool-blaze.mjs）逐字节对照的测试。
 * golden hex 由 node .tools/blaze-extract/gen-golden-json.mjs 生成，改动协议层后需重新生成并回填。
 */
class BlazeCodecTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun encodeGolden(method: String, id: Int, data: Map<String, Any?>, expectedHex: String) {
        val bytes = BlazeCodec.encode(method, BlazeCodec.TYPE_SEND_COMMAND, id, data)
        assertEquals(expectedHex, hex(bytes))
    }

    // ═══════════ golden 编码对照 ═══════════

    @Test
    fun loginPacketMatchesGolden() {
        encodeGolden(
            "Authentication.login", 1,
            mapOf(
                "AUTH 1" to "SOMEAUTHCODE123",
                "EXTB 2" to "",
                "EXTI 0" to 0L
            ),
            "0000001f00000001000a000001000000875d280110534f4d4541555448434f444531323300978d220200978d290000"
        )
    }

    @Test
    fun setClientStateMatchesGolden() {
        encodeGolden(
            "Util.setClientState", 2,
            mapOf("MODE 0" to 1L, "STAT 0" to 0L),
            "0000000a00000009001c000002000000b6f9250001cf48740000"
        )
    }

    @Test
    fun getFullGameDataMatchesGolden() {
        encodeGolden(
            "GameManager.getFullGameData", 3,
            mapOf("GIDL 40" to listOf(11295245190321L)),
            "0000000d0000000400670000030000009e992c040001b1929e91bc9105"
        )
    }

    @Test
    fun lookupUsersMatchesGolden() {
        encodeGolden(
            "30722.50", 4,
            mapOf("LTYP 1" to "cem_ea_id", "PLST 41" to listOf("SomePlayerName")),
            "00000025000078020032000004000000b34e70010a63656d5f65615f696400c2ccf40401010f536f6d65506c617965724e616d6500"
        )
    }

    private val networkInfo = mapOf(
        "EXIP 3" to mapOf("IP   0" to 1910937373L, "MACI 0" to 0L, "PORT 0" to 3659L),
        "INIP 3" to mapOf("IP   0" to 167772163L, "MACI 0" to 0L, "PORT 0" to 3659L),
        "MACI 0" to 546825851L
    )

    private val pingSites = mapOf(
        "aws-bom" to 118L, "aws-brz" to 370L, "aws-cdg" to -1601963046L, "aws-cmh" to 255L,
        "aws-dub" to 240L, "aws-dxb" to 340L, "aws-fra" to -1601963046L, "aws-hkg" to 105L,
        "aws-iad" to 260L, "aws-icn" to -1601963046L, "aws-lhr" to 210L, "aws-nrt" to 78L,
        "aws-pdx" to 230L, "aws-sin" to -1601963046L, "aws-sjc" to 200L, "aws-syd" to 150L,
        "m3d-hkg" to 130L, "m3d-jnb" to 390L
    )

    @Test
    fun updateNetworkInfoMatchesGolden() {
        encodeGolden(
            "UserSessions.updateNetworkInfo", 5,
            mapOf(
                "INFO 3" to mapOf(
                    "ADDR 62" to mapOf("VALU 3" to networkInfo),
                    "NLMP 510" to pingSites,
                    "NQOS 3" to mapOf(
                        "BWHR 0" to 0L, "DBPS 0" to 0L, "NAHR 0" to 0L, "NATT 0" to 3L, "UBPS 0" to 0L
                    )
                ),
                "OPTS 0" to 4L
            ),
            "00000146000078020014000005000000a6e9af038649320602da1b3503978a7003a70000009ddcb49e0eb618e90000c2fcb4008b3900a6ea7003a7000000838080a001b618e90000c2fcb4008b3900b618e900bb99bf890400bacb7005010012086177732d626f6d00b601086177732d62727a00b205086177732d63646700e68fe0f70b086177732d636d6800bf03086177732d64756200b003086177732d647862009405086177732d66726100e68fe0f70b086177732d686b6700a901086177732d696164008404086177732d69636e00e68fe0f70b086177732d6c6872009203086177732d6e7274008e01086177732d70647800a603086177732d73696e00e68fe0f70b086177732d736a63008803086177732d737964009602086d33642d686b67008202086d33642d6a6e62008606bb1bf3038b7a320000922c330000ba1a320000ba1d340003d62c3300000000bf0d330004"
        )
    }

    @Test
    fun joinGameMatchesGolden() {
        encodeGolden(
            "GameManager.joinGame", 6,
            mapOf(
                "CMGD 3" to mapOf(
                    "GGTY 0" to 0L,
                    "GVER 1" to "3779779",
                    "PNET 62" to mapOf("VALU 3" to networkInfo)
                ),
                "GID  0" to 11295245190321L,
                "JMET 0" to 1L,
                "PLJD 3" to mapOf(
                    "BTPL 9" to listOf(30722L, 2L, 12345L),
                    "DFRL 1" to "",
                    "GENT 0" to 2L,
                    "PLDL 43" to listOf(
                        mapOf(
                            "ENCR 1" to "",
                            "IREP 0" to 0L,
                            "PLYA 511" to mapOf("latency" to "-1"),
                            "RLNM 1" to "soldier",
                            "SLOT 0" to 1L,
                            "TID  0" to 65535L,
                            "TIDX 0" to 65535L,
                            "USID 3" to mapOf(
                                "AID  0" to 0L, "ALOC 0" to 0L, "CNTY 0" to 0L, "EXBB 2" to "",
                                "EXID 0" to 987654321L, "ID   0" to 123456789L, "NAME 1" to "",
                                "NASP 1" to "", "ORIG 0" to 0L, "PIDI 0" to 0L
                            )
                        )
                    ),
                    "SLOT 0" to 0L,
                    "TID  0" to 65534L,
                    "TIDX 0" to 65535L
                ),
                "SLID 0" to 255L,
                "USER 3" to mapOf(
                    "AID  0" to 0L, "ALOC 0" to 0L, "CNTY 0" to 0L, "EXBB 2" to "",
                    "EXID 0" to 0L, "ID   0" to 0L, "NAME 1" to "", "NASP 1" to "",
                    "ORIG 0" to 0L, "PIDI 0" to 0L
                )
            ),
            "0000015f0000000400090000060000008ed9e4039e7d3900009f697201083337373937373900c2e9740602da1b3503978a7003a70000009ddcb49e0eb618e90000c2fcb4008b3900a6ea7003a7000000838080a001b618e90000c2fcb4008b3900b618e900bb99bf890400009e990000b1929e91bc9105aad9740001c2caa4038b4c2c0982e00302b9c001926cac0101009e5bb40002c2c92c04030196e8f2010100a729700000c2ce6105010101086c6174656e637900032d3100cacbad0108736f6c6469657200cecbf40001d2990000bfff07d2993800bfff07d73a6403869900000086cbe300008eed3900009788a20200978a6400b1a2f3ad07a640000095b4de75ba1b65010100ba1cf0010100bf2a670000c2992900000000cecbf40000d2990000beff07d2993800bfff0700ceca6400bf03d7397203869900000086cbe300008eed3900009788a20200978a640000a640000000ba1b65010100ba1cf0010100bf2a670000c29929000000"
        )
    }

    @Test
    fun keepaliveBytesMatchGolden() {
        assertEquals(
            "00000000000000000000000000800000",
            hex(BlazeSocket.KEEPALIVE_BYTES)
        )
    }

    // ═══════════ 解码 ═══════════

    @Test
    fun loginResultRoundTrip() {
        val encoded = BlazeCodec.encode(
            "Authentication.login", BlazeCodec.TYPE_RESULT, 1,
            mapOf(
                "SESS 3" to mapOf(
                    "BUID 0" to 123456789L,
                    "PDTL 3" to mapOf("DSNM 1" to "SomePlayerName"),
                    "UID  0" to 987654321L
                ),
                "CGID 9" to listOf(30722L, 2L, 12345L)
            )
        )
        assertEquals(
            "0000003a00000001000a000001200000ce5cf3038b5a640095b4de75c24d2c03933bad010f536f6d65506c617965724e616d650000d6990000b1a2f3ad07008e7a640982e00302b9c001",
            hex(encoded)
        )

        val packet = BlazeCodec.decode(encoded)
        assertEquals("Authentication.login", packet.method)
        assertEquals(BlazeCodec.TYPE_RESULT, packet.type)
        assertEquals(1, packet.id)
        assertNull(packet.error)

        val sess = packet.data!!["SESS 3"] as Map<*, *>
        assertEquals(123456789L, sess["BUID 0"])
        assertEquals("SomePlayerName", (sess["PDTL 3"] as Map<*, *>)["DSNM 1"])
        assertEquals(987654321L, sess["UID  0"])
        assertEquals(listOf(30722L, 2L, 12345L), packet.data["CGID 9"])
    }

    @Test
    fun emptyResultDecodesWithEmptyData() {
        val packet = BlazeCodec.decode(hexToBytes("0000000000000009001c000002200000"))
        assertEquals("Util.setClientState", packet.method)
        assertEquals(BlazeCodec.TYPE_RESULT, packet.type)
        assertEquals(2, packet.id)
        assertNull(packet.error)
        assertNotNull(packet.data)
        assertTrue(packet.data!!.isEmpty())
    }

    /** ERRC=1074003968 (0x40040000) → Core 组件错误 4 = ERR_AUTHENTICATION_REQUIRED。 */
    @Test
    fun errorPacketDecodesBlazeError() {
        val packet = BlazeCodec.decode(hexToBytes("00000009000000040009000003000000972ca3008080a08008"))
        assertEquals("GameManager.joinGame", packet.method)
        assertEquals(3, packet.id)
        assertNull(packet.data)
        assertEquals(1074003968L, packet.errc)
        val error = packet.error!!
        assertEquals("Core", error.component)
        assertEquals("ERR_AUTHENTICATION_REQUIRED", error.name)
        assertTrue(error.description.orEmpty().contains("log in"))
    }

    @Test
    fun joinGameGoldenDecodesBackToSameStructure() {
        val encoded = hexToBytes(
            "0000015f0000000400090000060000008ed9e4039e7d3900009f697201083337373937373900c2e9740602da1b3503978a7003a70000009ddcb49e0eb618e90000c2fcb4008b3900a6ea7003a7000000838080a001b618e90000c2fcb4008b3900b618e900bb99bf890400009e990000b1929e91bc9105aad9740001c2caa4038b4c2c0982e00302b9c001926cac0101009e5bb40002c2c92c04030196e8f2010100a729700000c2ce6105010101086c6174656e637900032d3100cacbad0108736f6c6469657200cecbf40001d2990000bfff07d2993800bfff07d73a6403869900000086cbe300008eed3900009788a20200978a6400b1a2f3ad07a640000095b4de75ba1b65010100ba1cf0010100bf2a670000c2992900000000cecbf40000d2990000beff07d2993800bfff0700ceca6400bf03d7397203869900000086cbe300008eed3900009788a20200978a640000a640000000ba1b65010100ba1cf0010100bf2a670000c29929000000"
        )
        val packet = BlazeCodec.decode(encoded)
        assertEquals("GameManager.joinGame", packet.method)
        assertEquals(6, packet.id)
        assertNull(packet.error)
        val data = packet.data!!
        assertEquals(11295245190321L, data["GID  0"])
        val pljd = data["PLJD 3"] as Map<*, *>
        assertEquals(listOf(30722L, 2L, 12345L), pljd["BTPL 9"])
        val player = (pljd["PLDL 43"] as List<*>)[0] as Map<*, *>
        assertEquals("soldier", player["RLNM 1"])
        assertEquals(mapOf("latency" to "-1"), player["PLYA 511"])
        assertEquals("3779779", (data["CMGD 3"] as Map<*, *>)["GVER 1"])
    }

    /**
     * 真实成功的 Blaze 登录响应（2026-08-04 实测抓包）：成功响应同样携带 ERRC 0 字段，
     * 必须解析为成功而非错误（回归 CardTool.js 的真值判断）。
     */
    @Test
    fun realLoginResultWithErrcZeroIsNotAnError() {
        val packet = BlazeCodec.decode(hexToBytes(
            "000000ce00500001000a0000012000008eed380000972ca30000b619320300ceb979013c303030303234363835306136666563655f2a75524f4143366673656d57463630415551423554375075516b614b413344366c243259413534424a4c0086ebee0000ce5cf303463bee00008b5a640088f0b9b4d23a9b2cf400009e5bc00001ae5e40013c303030303234363835306136666563655f2a75524f4143366673656d57463630415551423554375075516b614b413344366c243259413534424a4c00b2cbe700b8d48aa70db61a6c0112323734333938343938394071712e636f6d00c24d2c03933bad0109414344435f32313000b21cf40000c299000088f0b9b4d23ac2c8740004cf48730000e3296600889b85f0913b00d6990000889b85f0913b00cf086d0000d6e9320000"
        ))
        assertEquals("Authentication.login", packet.method)
        assertEquals(BlazeCodec.TYPE_RESULT, packet.type)
        assertEquals(1, packet.id)
        assertNull(packet.error)
        assertNull(packet.errc)
        val data = packet.data!!
        assertEquals(0L, data["ERRC 0"])
        val sess = data["SESS 3"] as Map<*, *>
        assertEquals(1007493266440L, sess["BUID 0"])
        assertEquals("ACDC_210", (sess["PDTL 3"] as Map<*, *>)["DSNM 1"])
    }

    /**
     * 真实 UserSessions.UserAuthenticated 通知（登录成功后服务器主动推送，id=0）：
     * 同样带 ERRC 0，不应误判为错误。
     */
    @Test
    fun realUserAuthenticatedMessageWithErrcZeroIsNotAnError() {
        val packet = BlazeCodec.decode(hexToBytes(
            "000000db0015780200080000004001008eed38008efbb78a8a9a12972ca30000b619320300463bee000086cbe3008e8dc2a60f8b5a640088f0b9b4d23a8e7a640982e00302bef1f4b9e7c406933bad0109414344435f323130009b2cf40000ae5e40013c303030303234363835306136666563655f2a75524f4143366673656d57463630415551423554375075516b614b413344366c243259413534424a4c00b21cf400b8d48aa70db2cbe700b5d68aa70db61a6c0112323734333938343938394071712e636f6d00ba1cf0010a63656d5f65615f696400c299000088f0b9b4d23ac2c8740004d6990000889b85f0913bd73d300000e3296600889b85f0913b"
        ))
        assertEquals("UserSessions.UserAuthenticated", packet.method)
        assertEquals(BlazeCodec.TYPE_RECEIVE_MESSAGE, packet.type)
        assertEquals(0, packet.id)
        assertNull(packet.error)
        assertNull(packet.errc)
        val data = packet.data!!
        assertEquals(0L, data["ERRC 0"])
        assertEquals("ACDC_210", data["DSNM 1"])
        assertEquals(1007493266440L, data["BUID 0"])
    }

    // ═══════════ 整数边界 ═══════════

    @Test
    fun integerBoundariesRoundTrip() {
        val values = listOf(
            0L, 1L, 62L, 63L, 64L, 65L, 127L, 128L, 129L, 255L, 256L,
            16383L, 16384L, 1000000L, Int.MAX_VALUE.toLong(), 11295245190321L,
            // 负数：-64 及更小在 CardTool.js 中可正确往返
            -64L, -65L, -100L, -128L, -129L, -5000L, -1601963046L
        )
        for (v in values) {
            val encoded = BlazeCodec.encode("Util.setClientState", BlazeCodec.TYPE_RESULT, 1, mapOf("MODE 0" to v))
            val packet = BlazeCodec.decode(encoded)
            assertEquals("round-trip value $v", v, packet.data!!["MODE 0"])
        }
    }

    @Test
    fun stringsAndListsRoundTrip() {
        val encoded = BlazeCodec.encode(
            "30722.50", BlazeCodec.TYPE_RESULT, 1,
            mapOf(
                "LTYP 1" to "中文玩家名 émojis 🎮",
                "PLST 41" to listOf("", "a", "SomePlayerName", "中文")
            )
        )
        val packet = BlazeCodec.decode(encoded)
        assertEquals("中文玩家名 émojis 🎮", packet.data!!["LTYP 1"])
        assertEquals(listOf("", "a", "SomePlayerName", "中文"), packet.data["PLST 41"])
    }

    // ═══════════ PacketFramer ═══════════

    @Test
    fun framerAssemblesSplitPackets() {
        val packet = BlazeCodec.encode("Authentication.login", BlazeCodec.TYPE_SEND_COMMAND, 1, mapOf("AUTH 1" to "ABC"))
        val framer = PacketFramer()
        val collected = ArrayList<ByteArray>()
        // 逐字节喂入
        for (b in packet) collected.addAll(framer.feed(byteArrayOf(b)))
        assertEquals(1, collected.size)
        assertEquals(hex(packet), hex(collected[0]))
    }

    @Test
    fun framerHandlesMultiplePacketsInOneChunk() {
        val a = BlazeCodec.encode("Util.setClientState", BlazeCodec.TYPE_SEND_COMMAND, 1, mapOf("MODE 0" to 1L))
        val b = BlazeCodec.encode("GameManager.getFullGameData", BlazeCodec.TYPE_SEND_COMMAND, 2, mapOf("GIDL 40" to listOf(1L)))
        val combined = a + b
        val framer = PacketFramer()
        val packets = framer.feed(combined)
        assertEquals(2, packets.size)
        assertEquals(hex(a), hex(packets[0]))
        assertEquals(hex(b), hex(packets[1]))
    }

    @Test
    fun framerBuffersPartialAndFlushesAtBoundary() {
        val packet = BlazeCodec.encode("UserSessions.updateNetworkInfo", BlazeCodec.TYPE_SEND_COMMAND, 1, mapOf("OPTS 0" to 4L))
        val framer = PacketFramer()
        // 先喂前一半，再喂剩余
        val half = packet.size / 2
        assertTrue(framer.feed(packet.copyOfRange(0, half)).isEmpty())
        val rest = framer.feed(packet.copyOfRange(half, packet.size))
        assertEquals(1, rest.size)
        assertEquals(hex(packet), hex(rest[0]))
    }

    @Test
    fun framerDropsGarbageOnInvalidLength() {
        val framer = PacketFramer()
        val garbage = ByteArray(16) { 0x7f.toByte() }
        val out = framer.feed(garbage)
        assertTrue(out.isEmpty())
        // 内部缓冲应被清空，后续正常包仍可解析
        val packet = BlazeCodec.encode("Util.setClientState", BlazeCodec.TYPE_SEND_COMMAND, 1, mapOf("MODE 0" to 1L))
        val after = framer.feed(packet)
        assertEquals(1, after.size)
        assertEquals(hex(packet), hex(after[0]))
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i -> Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte() }
    }
}
