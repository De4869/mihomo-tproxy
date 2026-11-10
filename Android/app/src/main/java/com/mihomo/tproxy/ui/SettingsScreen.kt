package com.mihomo.tproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mihomo.tproxy.AppViewModel
import com.mihomo.tproxy.UiState

@Composable
fun SettingsScreen(
    state: UiState,
    vm: AppViewModel,
    onOpenApps: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val s = state.settings
    var dialog by remember { mutableStateOf<String?>(null) }
    var showAppUpdate by remember { mutableStateOf(false) }
    var showIpv6Warning by remember { mutableStateOf(false) }
    var showTlsWarning by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    androidx.compose.runtime.LaunchedEffect(state.appUpdateVersion) {
        if (state.appUpdateVersion.isNotBlank()) showAppUpdate = true
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Text("设置", color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("分流 · DNS · 内核 · 自启", color = Palette.textFaint, fontSize = 11.5.sp)

        Spacer(Modifier.height(16.dp))
        SectionLabel("外观")
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip("跟随系统", s.themeMode == "system", Modifier.weight(1f)) { vm.setTheme("system") }
                ThemeChip("深色", s.themeMode == "dark", Modifier.weight(1f)) { vm.setTheme("dark") }
                ThemeChip("浅色", s.themeMode == "light", Modifier.weight(1f)) { vm.setTheme("light") }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("代理")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                SettingRow("默认模式", modeLabel(s.mode)) {
                    Text(modeLabel(s.mode), color = Palette.primary, fontSize = 12.5.sp)
                }
                Divider()
                SettingRow("分流规则", ruleLabel(s.rulePreset), onClick = { dialog = "rule" })
                Divider()
                SettingRow(
                    "聚合节点过滤",
                    if (s.mergeFilter.isBlank()) "不过滤（整合全部节点）" else "排除：" + s.mergeFilter,
                    onClick = { dialog = "mergefilter" }
                )
                Divider()
                SettingRow("分应用代理", if (!s.perAppEnabled) "关闭" else (if (s.perAppWhitelist) "白名单 " else "黑名单 ") + s.perAppPackages.size + " 个应用") {
                    Switch(
                        checked = s.perAppEnabled,
                        onCheckedChange = { vm.applySettings(s.copy(perAppEnabled = it)) },
                        colors = switchColors()
                    )
                }
                if (s.perAppEnabled) {
                    Divider()
                    SettingRow("管理应用", "选择需要" + (if (s.perAppWhitelist) "代理" else "直连") + "的应用", onClick = onOpenApps)
                    Divider()
                    SettingRow("名单模式", if (s.perAppWhitelist) "白名单（仅选中走代理）" else "黑名单（选中直连）") {
                        Switch(
                            checked = s.perAppWhitelist,
                            onCheckedChange = { vm.applySettings(s.copy(perAppWhitelist = it)) },
                            colors = switchColors()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("网络")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                SettingRow("广告拦截", if (s.adBlock) "已开启 · 内置 GEOSITE + " + s.adSources.size + " 个在线规则" else "拦截常见广告与追踪域名") {
                    Switch(checked = s.adBlock, onCheckedChange = { vm.applySettings(s.copy(adBlock = it)) }, colors = switchColors())
                }
                if (s.adBlock) {
                    Divider()
                    val total = s.adSources.filter { it.count > 0 }.sumOf { it.count }
                    val last = s.adSources.maxOfOrNull { it.updatedAt } ?: 0L
                    SettingRow(
                        "在线规则源",
                        if (s.adSources.isEmpty()) "未添加（仅用内置规则）"
                        else s.adSources.size.toString() + " 个源 · " + total + " 条规则",
                        onClick = { dialog = "ads" }
                    )
                    Divider()
                    SettingRow("立即更新规则", "上次更新 " + fmtTime(last), onClick = { vm.updateAdRules() })
                    Divider()
                    SettingRow("规则自动更新", "每 " + s.adUpdateHours + " 小时", onClick = { dialog = "adint" })
                }
                Divider()
                SettingRow("DNS", dnsLabel(s.dnsPreset), onClick = { dialog = "dns" })
                Divider()
                SettingRow("IPv6", if (s.ipv6) "已开启 · 需节点支持 IPv6，否则可能影响 Google 下载" else "已关闭 · 全部走 IPv4，最稳定") {
                    Switch(
                        checked = s.ipv6,
                        onCheckedChange = { on ->
                            if (on) showIpv6Warning = true else vm.applySettings(s.copy(ipv6 = false))
                        },
                        colors = switchColors()
                    )
                }
                Divider()
                SettingRow("TUN 栈", s.tunStack, onClick = { dialog = "stack" })
                Divider()
                SettingRow("MTU", s.mtu.toString(), onClick = { dialog = "mtu" })
                Divider()
                SettingRow(
                    "跳过订阅证书校验",
                    if (s.skipTlsVerify) "已开启 · 不校验证书（仅订阅下载）"
                    else "校验证书（推荐）· 自签名/内网订阅可开启"
                ) {
                    Switch(
                        checked = s.skipTlsVerify,
                        onCheckedChange = { on -> if (on) showTlsWarning = true else vm.setSkipTlsVerify(false) },
                        colors = switchColors()
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("内核")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                SettingRow("状态", if (state.coreRunning) "运行中 · " + state.version else "未运行") {
                    Pill(if (state.coreRunning) "RUNNING" else "STOPPED", if (state.coreRunning) Palette.ok else Palette.textFaint)
                }
                Divider()
                SettingRow("检查更新", "当前内核 " + (state.version.ifBlank { com.mihomo.tproxy.core.CoreManager.VERSION_TAG }), onClick = { vm.checkUpdate() })
                if (state.updateVersion.isNotBlank()) {
                    Divider()
                    SettingRow(
                        "发现新内核 " + state.updateVersion,
                        if (state.updating) "下载并安装中 " + state.updateProgress + "%" else "点击立即更新（走当前代理下载）",
                        onClick = { if (!state.updating) vm.updateCore() }
                    )
                }
                Divider()
                SettingRow("日志等级", s.logLevel, onClick = { dialog = "log" })
                Divider()
                SettingRow("查看内核日志", "实时输出", onClick = onOpenLogs)
                Divider()
                SettingRow("开机自启", "开机后延迟启动内核") {
                    Switch(checked = s.bootStart, onCheckedChange = { vm.setBootStart(it) }, colors = switchColors())
                }
                Divider()
                SettingRow("重启内核", "重新加载并重启 mihomo", onClick = { vm.restartCore() })
                Divider()
                SettingRow("停止内核", "停止后网络恢复直连", onClick = { vm.stopCore() })
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("关于")
        GlassCard(Modifier.fillMaxWidth(), padding = 14) {
            Column {
                SettingRow("TunClash", "v" + com.mihomo.tproxy.BuildConfig.VERSION_NAME, onClick = {}, vertical = 10)
                Divider()
                SettingRow("项目地址", "github.com/De4869/tunclash", onClick = {
                    runCatching { uriHandler.openUri("https://github.com/De4869/tunclash") }
                }, vertical = 10)
                Divider()
                SettingRow(
                    "检查更新",
                    when {
                        state.appUpdateVersion.isNotBlank() -> "发现新版本 v" + state.appUpdateVersion
                        state.appUpdateLatest.isNotBlank() -> "已是最新版本 v" + state.appUpdateLatest
                        state.appUpdateError.isNotBlank() -> state.appUpdateError
                        else -> "检查 App 是否有新版本"
                    },
                    onClick = {
                        if (state.appUpdateVersion.isNotBlank()) showAppUpdate = true else vm.checkAppUpdate()
                    },
                    vertical = 8
                )
            }
        }

        Spacer(Modifier.height(110.dp))
    }

    if (showIpv6Warning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showIpv6Warning = false },
            containerColor = Palette.bg2,
            title = { Text("开启 IPv6？", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "开启后 IPv6 流量会进入隧道、经代理转发，需要当前节点支持 IPv6。\n\n" +
                        "⚠️ 节点不支持 IPv6 时：Google Play 等应用可能无法下载/更新（一直停在等待中），" +
                        "部分 IPv6 站点也会连接失败；IPv4 一般会回退，但下载类应用回退较慢。\n\n" +
                        "⚠️ 还可能出现真实 IPv6 地址泄漏。\n\n" +
                        "不确定节点是否支持时，建议保持关闭——日常上网与下载不受影响。",
                    color = Palette.textDim, fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                Text("仍然开启", color = Palette.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickableNoRipple {
                        showIpv6Warning = false
                        vm.applySettings(state.settings.copy(ipv6 = true))
                    }.padding(8.dp))
            },
            dismissButton = {
                Text("保持关闭", color = Palette.textDim,
                    modifier = Modifier.clickableNoRipple { showIpv6Warning = false }.padding(8.dp))
            }
        )
    }

    if (showTlsWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTlsWarning = false },
            containerColor = Palette.bg2,
            title = { Text("跳过证书校验？", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "开启后，下载订阅时不再校验证书，可连接使用自签名证书或内网 HTTPS 的服务器。\n\n" +
                        "⚠️ 这会降低安全性：对订阅服务器的身份不再有任何保证，可能被中间人篡改配置。\n\n" +
                        "仅在你信任订阅服务器时开启；普通公网订阅建议保持关闭。",
                    color = Palette.textDim, fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                Text("仍然开启", color = Palette.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickableNoRipple {
                        showTlsWarning = false
                        vm.setSkipTlsVerify(true)
                    }.padding(8.dp))
            },
            dismissButton = {
                Text("取消", color = Palette.textDim,
                    modifier = Modifier.clickableNoRipple { showTlsWarning = false }.padding(8.dp))
            }
        )
    }

    if (showAppUpdate && state.appUpdateVersion.isNotBlank()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAppUpdate = false; vm.dismissAppUpdate() },
            containerColor = Palette.bg2,
            title = {
                Text("发现新版本 v" + state.appUpdateVersion, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (state.appUpdateNotes.isBlank()) "暂无更新说明" else state.appUpdateNotes,
                        color = Palette.textDim,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Text(
                    "下载更新",
                    color = Palette.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickableNoRipple {
                        runCatching {
                            uriHandler.openUri(
                                state.appUpdateUrl.ifBlank { "https://github.com/De4869/tunclash/releases/latest" }
                            )
                        }
                        showAppUpdate = false
                        vm.dismissAppUpdate()
                    }.padding(8.dp)
                )
            },
            dismissButton = {
                Text("稍后", color = Palette.textDim, modifier = Modifier.clickableNoRipple {
                    showAppUpdate = false
                    vm.dismissAppUpdate()
                }.padding(8.dp))
            }
        )
    }

    if (dialog == "mergefilter") {
        com.mihomo.tproxy.ui.MergeFilterDialog(s.mergeFilter, { dialog = null }) { v ->
            vm.applySettings(s.copy(mergeFilter = v))
            dialog = null
        }
    }

    if (dialog == "ads") {
        com.mihomo.tproxy.ui.AdSourcesDialog(s, vm) { dialog = null }
    }

    when (dialog) {
        "rule" -> ChoiceDialog(
            "分流规则", listOf(
                "smart" to "智能分流（国内直连 / 国外代理）",
                "global" to "全局代理",
                "direct" to "全局直连"
            ), s.rulePreset, { dialog = null }
        ) { vm.applySettings(s.copy(rulePreset = it)) }

        "adint" -> ChoiceDialog(
            "规则自动更新", listOf(
                "6" to "每 6 小时",
                "12" to "每 12 小时",
                "24" to "每天（默认）",
                "48" to "每 2 天"
            ), s.adUpdateHours.toString(), { dialog = null }
        ) { vm.applySettings(s.copy(adUpdateHours = it.toIntOrNull() ?: 24)) }

        "dns" -> ChoiceDialog(
            "DNS", listOf(
                "cn" to "国内优化（阿里 / 腾讯 DoH）",
                "google" to "Google DNS",
                "cf" to "Cloudflare DNS"
            ), s.dnsPreset, { dialog = null }
        ) { vm.applySettings(s.copy(dnsPreset = it)) }

        "stack" -> ChoiceDialog(
            "TUN 栈", listOf(
                "mixed" to "Mixed（推荐）",
                "gvisor" to "gVisor（兼容性最好）",
                "system" to "System（性能最好）"
            ), s.tunStack, { dialog = null }
        ) { vm.applySettings(s.copy(tunStack = it)) }

        "mtu" -> ChoiceDialog(
            "MTU", listOf("9000" to "9000（推荐）", "1500" to "1500（兼容性）", "4064" to "4064"),
            s.mtu.toString(), { dialog = null }
        ) { vm.applySettings(s.copy(mtu = it.toIntOrNull() ?: 9000)) }

        "log" -> ChoiceDialog(
            "日志等级", listOf("info" to "info", "warning" to "warning", "error" to "error", "debug" to "debug"),
            s.logLevel, { dialog = null }
        ) { vm.applySettings(s.copy(logLevel = it)) }
    }
}

private fun fmtTime(ts: Long): String =
    if (ts <= 0L) "未更新"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

@Composable
private fun ThemeChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Palette.primary.copy(alpha = 0.18f) else Palette.card)
            .clickableNoRipple { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (on) Palette.primary else Palette.textDim,
            fontSize = 12.5.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp).background(Palette.cardBorder))
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    vertical: Int = 12,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { onClick() }
                else Modifier
            )
            .padding(vertical = vertical.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 13.5.sp)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Palette.textFaint, fontSize = 10.5.sp)
            }
        }
        if (trailing != null) trailing()
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Palette.textFaint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Palette.primary,
    uncheckedThumbColor = Palette.textFaint,
    uncheckedTrackColor = Palette.track,
    uncheckedBorderColor = Color.Transparent
)

private fun modeLabel(m: String) = when (m) {
    "global" -> "全局代理"
    "direct" -> "全局直连"
    else -> "规则模式"
}

private fun ruleLabel(r: String) = when (r) {
    "global" -> "全局代理"
    "direct" -> "全局直连"
    else -> "智能分流"
}

private fun dnsLabel(d: String) = when (d) {
    "google" -> "Google"
    "cf" -> "Cloudflare"
    "custom" -> "自定义"
    else -> "国内优化"
}