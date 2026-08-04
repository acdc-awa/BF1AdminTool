package com.bf1.admin.tool.blaze

/**
 * Blaze 请求包构造，数据结构与 CardTool.js 完全一致（.tools/blaze-extract 的 golden 已验证编码）。
 */
object BlazePackets {

    /** CARDTOOL_NETWORK_INFO：进服前上报的伪网络信息。 */
    val NETWORK_INFO: Map<String, Any?> = mapOf(
        "EXIP 3" to mapOf("IP   0" to 1910937373L, "MACI 0" to 0L, "PORT 0" to 3659L),
        "INIP 3" to mapOf("IP   0" to 167772163L, "MACI 0" to 0L, "PORT 0" to 3659L),
        "MACI 0" to 546825851L
    )

    /** PING_SITES：进服前上报的节点延迟表。 */
    val PING_SITES: Map<String, Any?> = mapOf(
        "aws-bom" to 118L, "aws-brz" to 370L, "aws-cdg" to -1601963046L, "aws-cmh" to 255L,
        "aws-dub" to 240L, "aws-dxb" to 340L, "aws-fra" to -1601963046L, "aws-hkg" to 105L,
        "aws-iad" to 260L, "aws-icn" to -1601963046L, "aws-lhr" to 210L, "aws-nrt" to 78L,
        "aws-pdx" to 230L, "aws-sin" to -1601963046L, "aws-sjc" to 200L, "aws-syd" to 150L,
        "m3d-hkg" to 130L, "m3d-jnb" to 390L
    )

    fun login(authCode: String): Map<String, Any?> = mapOf(
        "AUTH 1" to authCode,
        "EXTB 2" to "",
        "EXTI 0" to 0L
    )

    fun lookupUsers(displayName: String): Map<String, Any?> = mapOf(
        "LTYP 1" to "cem_ea_id",
        "PLST 41" to listOf(displayName)
    )

    fun setClientState(mode: Int): Map<String, Any?> = mapOf(
        "MODE 0" to mode.toLong(),
        "STAT 0" to 0L
    )

    fun updateNetworkInfo(): Map<String, Any?> = mapOf(
        "INFO 3" to mapOf(
            "ADDR 62" to mapOf("VALU 3" to NETWORK_INFO),
            "NLMP 510" to PING_SITES,
            "NQOS 3" to mapOf(
                "BWHR 0" to 0L, "DBPS 0" to 0L, "NAHR 0" to 0L, "NATT 0" to 3L, "UBPS 0" to 0L
            )
        ),
        "OPTS 0" to 4L
    )

    fun getFullGameData(gameId: Long): Map<String, Any?> = mapOf(
        "GIDL 40" to listOf(gameId)
    )

    /**
     * joinGame 包（direct 直连风格，与 CardTool 的 buildJoinGamePacket 一致）。
     * [connectionGroup] 优先取 lookupUsers 的 userExtendedData 前三项，否则 [30722, 2, connectionGroupId]。
     */
    fun joinGame(
        gameId: Long,
        personaId: Long,
        platformId: Long,
        connectionGroup: List<Long>,
        protocolVersion: String,
        role: String = "soldier",
        gent: Int = 0
    ): Map<String, Any?> {
        val emptyUser = mapOf(
            "AID  0" to 0L, "ALOC 0" to 0L, "CNTY 0" to 0L, "EXBB 2" to "", "EXID 0" to 0L,
            "ID   0" to 0L, "NAME 1" to "", "NASP 1" to "", "ORIG 0" to 0L, "PIDI 0" to 0L
        )
        return mapOf(
            "CMGD 3" to mapOf(
                "GGTY 0" to 0L,
                "GVER 1" to protocolVersion.ifEmpty { "3779779" },
                "PNET 62" to mapOf("VALU 3" to NETWORK_INFO)
            ),
            "GID  0" to gameId,
            "JMET 0" to 1L,
            "PLJD 3" to mapOf(
                "BTPL 9" to connectionGroup,
                "DFRL 1" to "",
                "GENT 0" to gent.toLong(),
                "PLDL 43" to listOf(
                    mapOf(
                        "ENCR 1" to "",
                        "IREP 0" to 0L,
                        "PLYA 511" to mapOf("latency" to "-1"),
                        "RLNM 1" to role,
                        "SLOT 0" to 1L,
                        "TID  0" to 65535L,
                        "TIDX 0" to 65535L,
                        "USID 3" to mapOf(
                            "AID  0" to 0L, "ALOC 0" to 0L, "CNTY 0" to 0L, "EXBB 2" to "",
                            "EXID 0" to platformId, "ID   0" to personaId, "NAME 1" to "",
                            "NASP 1" to "", "ORIG 0" to 0L, "PIDI 0" to 0L
                        )
                    )
                ),
                "SLOT 0" to 0L,
                "TID  0" to 65534L,
                "TIDX 0" to 65535L
            ),
            "SLID 0" to 255L,
            "USER 3" to emptyUser
        )
    }
}
