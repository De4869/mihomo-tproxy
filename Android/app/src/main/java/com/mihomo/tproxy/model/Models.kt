package com.mihomo.tproxy.model

/** A subscription / configuration source owned by the user. */
data class Profile(
    val id: String,
    val name: String,
    val kind: String,          // url | links | yaml | merge
    val url: String = "",
    val content: String = "",
    val updatedAt: Long = 0L,
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L,
    val nodeCount: Int = 0
) {
    val isMerge: Boolean get() = kind == "merge"
}

data class RuleSource(
    val name: String,
    val url: String,
    val behavior: String = "domain",
    val format: String = "yaml",
    val count: Int = -1,          // -1 = 未知（如 mrs 二进制）
    val updatedAt: Long = 0L      // 上次成功拉取的时间
)

data class ProxySettings(
    val themeMode: String = "system",   // system | dark | light
    val mode: String = "rule",              // rule | global | direct
    val rulePreset: String = "smart",       // smart | global | direct
    val dnsPreset: String = "cn",           // cn | google | cf | custom
    val customDns: String = "",
    val ipv6: Boolean = false,
    val tunStack: String = "mixed",         // mixed | gvisor | system
    val mtu: Int = 9000,
    val logLevel: String = "info",
    val perAppEnabled: Boolean = false,
    val perAppWhitelist: Boolean = false,
    val perAppPackages: Set<String> = emptySet(),
    val perAppUids: Set<Int> = emptySet(),
    val adBlock: Boolean = false,
    val adSources: List<RuleSource> = emptyList(),
    val adUpdateHours: Int = 24,   // 在线规则自动更新间隔（小时）
    val mergeFilter: String = "",  // 聚合时排除包含这些关键词的节点，逗号分隔
    val skipTlsVerify: Boolean = false, // 订阅/规则下载跳过 TLS 证书校验（自签名、内网）
    val bootStart: Boolean = false,
    val secret: String = "mihomo-tproxy"
)

data class NodeInfo(
    val name: String,
    val type: String,
    val delay: Int = 0,
    val alive: Boolean = true,
    val udp: Boolean = false,
    val group: String = ""
)

data class GroupInfo(
    val name: String,
    val type: String,
    val now: String = "",
    val nodes: List<String> = emptyList()
)

data class TrafficSample(val up: Long = 0, val down: Long = 0)