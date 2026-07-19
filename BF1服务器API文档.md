# 战地1 (Battlefield 1) 服务器管理 API 文档

## 概述

**API端点**: `https://sparta-gw.battlelog.com/jsonrpc/pc/api`

**协议**: JSON-RPC 2.0

**游戏ID**: `tunguska` (BF1内部代号)

---

## 通用请求头

| 头字段 | 值 |
|---------|-----|
| `User-Agent` | `ProtoHttp 1.3/DS 15.1.2.1.0 (Windows)` |
| `X-GatewaySession` | `{SessionId}` - 会话ID |
| `X-ClientVersion` | `release-bf1-lsu35_26385_ad7bf56a_tunguska_all_prod` |
| `X-DbId` | `Tunguska.Shipping2PC.Win32` |
| `X-CodeCL` | `3779779` |
| `X-DataCL` | `3779779` |
| `X-SaveGameVersion` | `26` |
| `X-HostingGameId` | `tunguska` |
| `X-Sparta-Info` | `tenancyRootEnv=unknown; tenancyBlazeEnv=unknown` |
| `Content-Type` | `application/json` |

---

## 通用请求格式

```json
{
  "jsonrpc": "2.0",
  "method": "{方法名}",
  "params": {
    "game": "tunguska",
    ...其他参数
  },
  "id": "{UUID}"
}
```

---

## 1. 玩家管理 API

### 1.1 踢出玩家

**方法名**: `RSP.kickPlayer`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `gameId` | string | 是 | 当前游戏服务器ID |
| `personaId` | string | 是 | 玩家ID |
| `reason` | string | 是 | 踢出原因 |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.kickPlayer",
  "params": {
    "game": "tunguska",
    "gameId": "xxx-xxx-xxx",
    "personaId": "123456789",
    "reason": "违反服务器规则"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 1.2 更换玩家队伍

**方法名**: `RSP.movePlayer`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `personaId` | string | 是 | 玩家ID |
| `gameId` | string | 是 | 当前游戏服务器ID |
| `teamId` | string | 是 | 目标队伍ID |
| `forceKill` | boolean | 是 | 是否强制击杀 (建议: `true`) |
| `moveParty` | boolean | 是 | 是否移动小队 (建议: `false`) |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.movePlayer",
  "params": {
    "game": "tunguska",
    "personaId": "123456789",
    "gameId": "xxx-xxx-xxx",
    "teamId": "1",
    "forceKill": true,
    "moveParty": false
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 2. 管理员管理 API

### 2.1 添加管理员

**方法名**: `RSP.addServerAdmin`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |
| `personaName` | string | 是 | 玩家名称 |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.addServerAdmin",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx",
    "personaName": "PlayerName"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 2.2 移除管理员

**方法名**: `RSP.removeServerAdmin`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |
| `personaId` | string | 是 | 玩家ID |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.removeServerAdmin",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx",
    "personaId": "123456789"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 3. VIP管理 API

### 3.1 添加VIP

**方法名**: `RSP.addServerVip`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |
| `personaName` | string | 是 | 玩家名称 |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.addServerVip",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx",
    "personaName": "PlayerName"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 3.2 移除VIP

**方法名**: `RSP.removeServerVip`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |
| `personaId` | string | 是 | 玩家ID |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.removeServerVip",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx",
    "personaId": "123456789"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 4. 封禁管理 API

### 4.1 添加封禁

**方法名**: `RSP.addServerBan`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |
| `personaName` | string | 是 | 玩家名称 |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.addServerBan",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx",
    "personaName": "PlayerName"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 4.2 移除封禁

**方法名**: `RSP.removeServerBan`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |
| `personaId` | string | 是 | 玩家ID |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.removeServerBan",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx",
    "personaId": "123456789"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 5. 地图管理 API

### 5.1 更换地图

**方法名**: `RSP.chooseLevel`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `persistedGameId` | string | 是 | 持久化游戏ID |
| `levelIndex` | string | 是 | 地图索引 |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.chooseLevel",
  "params": {
    "game": "tunguska",
    "persistedGameId": "xxx-xxx-xxx",
    "levelIndex": "0"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 6. 服务器信息 API

### 6.1 获取完整服务器详情

**方法名**: `GameServer.getFullServerDetails`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `gameId` | string | 是 | 当前游戏服务器ID |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "GameServer.getFullServerDetails",
  "params": {
    "game": "tunguska",
    "gameId": "xxx-xxx-xxx"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**响应数据结构**:
```json
{
  "jsonrpc": "2.0",
  "id": "...",
  "result": {
    "serverInfo": {
      "gameId": "xxx",
      "name": "服务器名称",
      "description": "描述",
      "mapName": "MP_Suez",
      "mapMode": "ConquestLarge0",
      "slots": {},
      "settings": {}
    },
    "rspInfo": {
      "adminList": [
        {
          "personaId": "123",
          "displayName": "管理员名称",
          "avatar": "..."
        }
      ],
      "vipList": [],
      "bannedList": [],
      "mapRotations": [],
      "serverSettings": {}
    }
  }
}
```

---

### 6.2 获取服务器RSP详情

**方法名**: `RSP.getServerDetails`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.getServerDetails",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**响应数据结构**:
```json
{
  "jsonrpc": "2.0",
  "id": "...",
  "result": {
    "adminList": [
      {
        "personaId": "123",
        "displayName": "管理员",
        "platform": "pc",
        "nucleusId": "...",
        "platformId": "...",
        "avatar": "...",
        "accountId": "..."
      }
    ],
    "vipList": [],
    "bannedList": [],
    "mapRotations": [
      {
        "mapRotationId": "...",
        "name": "轮换名称",
        "mod": "Conquest",
        "maps": [
          {
            "gameMode": "ConquestLarge0",
            "mapName": "MP_Suez"
          }
        ]
      }
    ],
    "owner": {},
    "server": {
      "serverId": "...",
      "persistedGameId": "...",
      "name": "服务器名称",
      "bannerUrl": "...",
      "status": {
        "value": 1,
        "name": "Available"
      }
    },
    "serverSettings": {
      "name": "服务器名称",
      "description": "描述",
      "message": "欢迎消息",
      "password": "",
      "mapRotationId": "...",
      "bannerUrl": "...",
      "customGameSettings": "..."
    }
  }
}
```

---

## 7. 更新服务器配置 API

### 7.1 更新服务器信息

**方法名**: `RSP.updateServer`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `serverId` | string | 是 | 服务器ID |
| `deviceIdMap` | object | 是 | 设备ID映射 |
| `bannerSettings` | object | 否 | Banner设置 |
| `mapRotation` | object | 否 | 地图轮换 |
| `serverSettings` | object | 否 | 服务器设置 |

**完整请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "RSP.updateServer",
  "params": {
    "game": "tunguska",
    "serverId": "xxx-xxx-xxx",
    "deviceIdMap": {
      "machash": "..."
    },
    "bannerSettings": {
      "bannerUrl": "https://example.com/banner.jpg",
      "clearBanner": false
    },
    "mapRotation": {
      "maps": [
        {
          "gameMode": "ConquestLarge0",
          "mapName": "MP_Suez"
        },
        {
          "gameMode": "ConquestLarge0",
          "mapName": "MP_Desert"
        }
      ],
      "rotationType": "standard",
      "mod": "Conquest",
      "name": "我的轮换",
      "description": "自定义地图轮换",
      "id": "xxx-xxx-xxx"
    },
    "serverSettings": {
      "name": "服务器新名称",
      "description": "服务器描述",
      "message": "欢迎来到服务器！",
      "password": "",
      "bannerUrl": "https://example.com/banner.jpg",
      "mapRotationId": "xxx-xxx-xxx",
      "customGameSettings": "..."
    }
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 8. 辅助 API

### 8.1 获取欢迎消息

**方法名**: `Onboarding.welcomeMessage`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `minutesToUTC` | string | 是 | 时区偏移 (中国: `"-480"`) |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "Onboarding.welcomeMessage",
  "params": {
    "game": "tunguska",
    "minutesToUTC": "-480"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 8.2 设置API语言

**方法名**: `CompanionSettings.setLocale`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `locale` | string | 是 | 语言代码 (建议: `"zh_TW"`) |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "CompanionSettings.setLocale",
  "params": {
    "locale": "zh_TW"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 8.3 通过AuthCode获取EnvId

**方法名**: `Authentication.getEnvIdViaAuthCode`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `authCode` | string | 是 | 授权码 |
| `locale` | string | 是 | 语言 (建议: `"zh-tw"`) |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "Authentication.getEnvIdViaAuthCode",
  "params": {
    "authCode": "xxx",
    "locale": "zh-tw"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### 8.4 获取玩家游戏生涯

**方法名**: `Stats.getCareerForOwnedGamesByPersonaId`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `game` | string | 是 | 固定值: `"tunguska"` |
| `personaId` | string | 是 | 玩家ID |

**请求示例**:
```json
{
  "jsonrpc": "2.0",
  "method": "Stats.getCareerForOwnedGamesByPersonaId",
  "params": {
    "game": "tunguska",
    "personaId": "123456789"
  },
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 附录 A: 常用地图名称

| 地图代码 | 地图名称 |
|---------|---------|
| `MP_Alps` | 阿尔卑斯山 |
| `MP_Amiens` | 亚眠 |
| `MP_Beachhead` | 海丽丝岬 |
| `MP_Blitz` | 帝国边境 |
| `MP_Bridge` | 勃鲁希洛夫关口 |
| `MP_Chateau` | 法欧堡 |
| `MP_Desert` | 西奈半岛 |
| `MP_FaoFortress` | 法欧堡 |
| `MP_Fields` | 索姆河 |
| `MP_Forest` | 阿尔贡森林 |
| `MP_Giant` | 圣康坦的伤痕 |
| `MP_Graveyard` | 尼维尔之夜 |
| `MP_Harbor` | 泽布勒赫 |
| `MP_Hell` | 炼狱 |
| `MP_Islands` | 海尔湾 |
| `MP_ItalianCoast` | 卡波雷托 |
| `MP_London` | 伦敦的呼唤 |
| `MP_MountainFort` | 蒙格拉巴 |
| `MP_Naval` | 黑尔戈兰湾 |
| `MP_Offensive` | 武普库夫山口 |
| `MP_Ravines` | 巴拉顿湖 |
| `MP_Ridge` | 索查河 |
| `MP_River` | 突破 |
| `MP_Scar` | 大歌剧院 |
| `MP_Shoveltown` | 帕斯尚尔 |
| `MP_Suez` | 苏伊士 |
| `MP_Trench` | 前线 |
| `MP_Tsaritsyn` | 察里津 |
| `MP_Underworld` | 地下世界 |
| `MP_Valley` | 卢克索 |
| `MP_Verdun` | 凡尔登高地 |
| `MP_Volga` | 伏尔加河 |

---

## 附录 B: 常用游戏模式

| 模式代码 | 模式名称 |
|---------|---------|
| `ConquestLarge0` | 大型征服 |
| `ConquestSmall0` | 小型征服 |
| `Domination0` | 行动模式 |
| `TeamDeathMatch0` | 团队死斗 |
| `RushLarge0` | 突袭 |
| `BreakthroughLarge0` | 突破 |
| `AirAssault0` | 空中突袭 |

---

## 错误响应格式

```json
{
  "jsonrpc": "2.0",
  "id": "...",
  "error": {
    "code": 1234,
    "message": "错误描述"
  }
}
```

---

## 全局变量说明

| 变量名 | 说明 | 来源 |
|--------|------|------|
| `SessionId` | 会话ID | 登录后获取，用于 `X-GatewaySession` 头 |
| `GameId` | 当前游戏ID | 从服务器详情获取 |
| `ServerId` | 服务器ID | 从服务器详情获取 |
| `Remid` | 持久化ID | 本地配置保存 |
| `Sid` | 会话SID | 本地配置保存 |
