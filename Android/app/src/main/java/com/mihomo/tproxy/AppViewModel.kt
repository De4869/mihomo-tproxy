package com.mihomo.tproxy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mihomo.tproxy.core.ConfigComposer
import com.mihomo.tproxy.core.CoreUpdater
import com.mihomo.tproxy.core.CoreManager
import com.mihomo.tproxy.core.MihomoApi
import com.mihomo.tproxy.core.RootShell
import com.mihomo.tproxy.data.Store
import com.mihomo.tproxy.data.SubFetcher
import com.mihomo.tproxy.model.GroupInfo
import com.mihomo.tproxy.model.NodeInfo
import com.mihomo.tproxy.model.Profile
import com.mihomo.tproxy.model.ProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class UiState(
    val ready: Boolean = false,
    val rootOk: Boolean = false,
    val installing: String? = null,
    val coreRunning: Boolean = false,
    val paused: Boolean = false,
    val mode: String = "rule",
    val profiles: List<Profile> = emptyList(),
    val activeId: String = "",
    val groups: List<GroupInfo> = emptyList(),
    val nodes: List<NodeInfo> = emptyList(),
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val startedAt: Long = 0,
    val logs: List<String> = emptyList(),
    val toast: String? = null,
    val busy: Boolean = false,
    val version: String = "",
    val platforms: List<PlatformResult> = emptyList(),
    val appUpdateChecked: Boolean = false,
    val appUpdateLatest: String = "",     // 已是最新的版本号
    val appUpdateVersion: String = "",    // 发现的新版本
    val appUpdateNotes: String = "",
    val appUpdateUrl: String = "",
    val appUpdateError: String = "",
    val updateVersion: String = "",
    val updateUrl: String = "",
    val updating: Boolean = false,
    val updateProgress: Int = 0,
    val settings: ProxySettings = ProxySettings()
) {
    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeId }
    val connected: Boolean get() = coreRunning && !paused
    val currentGroup: GroupInfo? get() = groups.firstOrNull()
}

data class PlatformResult(
    val name: String,
    val emoji: String,
    val host: String,
    val state: String = "idle", // idle | testing | ok | fail
    val ms: Long = 0
)

data class PlatformTarget(val name: String, val emoji: String, val url: String, val direct: Boolean = false)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val core = CoreManager(app)
    private val api = MihomoApi(store.secret)

    private val _state = MutableStateFlow(UiState(settings = store.settings))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var trafficJob: Job? = null
    private var logJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { bootstrap() }
    }

    private fun refreshNodeCounts() {
        var changed = false
        val fixed = store.profiles.map { p ->
            if (p.content.isNotBlank() && p.nodeCount == 0) {
                changed = true
                p.copy(nodeCount = ConfigComposer.nodeCount(p.content))
            } else p
        }
        if (changed) store.profiles = fixed
    }

    private suspend fun bootstrap() {
        refreshNodeCounts()
        val root = RootShell.hasRoot()
        _state.update { it.copy(rootOk = root, ready = true, profiles = store.profiles, activeId = store.activeId) }
        if (!root) return
        if (!ensureInstalled()) return
        refreshStatus()
        startStreams()
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                delay(3000)
                refreshStatus(light = true)
                tick++
                if (tick % 2 == 0 && _state.value.coreRunning) refreshProxies()
            }
        }
    }

    private suspend fun ensureInstalled(): Boolean {
        val installed = core.installedVersion()
        if (installed.isNotBlank() && core.binaryExists()) return true
        _state.update { it.copy(installing = "安装内核…") }
        val r = core.installCore { step -> _state.update { it.copy(installing = step) } }
        _state.update { it.copy(installing = null) }
        if (r.isFailure) {
            _state.update { it.copy(toast = r.exceptionOrNull()?.message ?: "内核安装失败") }
            return false
        }
        return true
    }

    fun retryRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            RootShell.invalidate()
            _state.update { it.copy(installing = "检查 Root 权限…") }
            val ok = RootShell.hasRoot()
            _state.update { it.copy(rootOk = ok, installing = null) }
            if (!ok) {
                toast("仍未获得 Root 权限，请在 KernelSU 中允许本应用")
                return@launch
            }
            if (ensureInstalled()) {
                refreshStatus()
                startStreams()
            }
        }
    }

    private val platformTargets = listOf(
        PlatformTarget("Google", "🇬", "https://www.google.com/generate_204"),
        PlatformTarget("YouTube", "▶", "https://www.youtube.com/generate_204"),
        PlatformTarget("ChatGPT", "🤖", "https://chatgpt.com/cdn-cgi/trace"),
        PlatformTarget("GitHub", "🐙", "https://github.com/favicon.ico"),
        PlatformTarget("Netflix", "🎬", "https://www.netflix.com/"),
        PlatformTarget("Telegram", "✈", "https://telegram.org/"),
        PlatformTarget("X / Twitter", "𝕏", "https://x.com/"),
        PlatformTarget("Spotify", "🎧", "https://open.spotify.com/"),
        PlatformTarget("Instagram", "📷", "https://www.instagram.com/"),
        PlatformTarget("百度（直连）", "🇨🇳", "https://www.baidu.com", direct = true)
    )

    private fun hostOf(url: String): String = runCatching { java.net.URL(url).host }.getOrDefault(url)

    /** Validates with "mihomo -t" before touching the live config. */
    private fun writeValidated(yaml: String): Boolean {
        val (ok, out) = core.validateConfig(yaml)
        if (!ok) {
            val line = out.lines().firstOrNull { it.contains("fatal", true) || it.contains("error", true) }
                ?: out.lines().lastOrNull { it.isNotBlank() } ?: ""
            val clean = line.substringAfter("msg=", line).trim().trim('"')
            toast("配置校验未通过：" + clean.take(120))
            return false
        }
        if (!core.writeConfig(yaml)) {
            toast("写入配置失败")
            return false
        }
        return true
    }

    fun testPlatforms() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!_state.value.coreRunning) {
                toast("请先开启代理再检测")
                return@launch
            }
            _state.update { st ->
                st.copy(platforms = platformTargets.map { PlatformResult(it.name, it.emoji, hostOf(it.url), "testing") })
            }
            val jobs = platformTargets.map { t ->
                viewModelScope.launch(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    var ok = false
                    try {
                        val url = java.net.URL(t.url)
                        val conn = (if (t.direct) url.openConnection()
                        else url.openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 7890)))
                            ) as java.net.HttpURLConnection
                        conn.connectTimeout = 6000
                        conn.readTimeout = 8000
                        conn.instanceFollowRedirects = false
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                        val code = conn.responseCode
                        conn.disconnect()
                        ok = code in 200..499
                    } catch (_: Exception) {
                        ok = false
                    }
                    val ms = System.currentTimeMillis() - start
                    _state.update { st ->
                        st.copy(platforms = st.platforms.map { r ->
                            if (r.name == t.name) r.copy(state = if (ok) "ok" else "fail", ms = ms) else r
                        })
                    }
                }
            }
            jobs.forEach { it.join() }
            val okCount = _state.value.platforms.count { it.state == "ok" }
            toast("检测完成：" + okCount + "/" + platformTargets.size + " 可用")
        }
    }

    // ------------------------------------------------------------ core update

    fun checkUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val running = _state.value.coreRunning
            toast("正在检查更新…")
            val rel = CoreUpdater.check(running)
            if (rel == null) {
                toast("检查失败：无法访问 GitHub（可先开启代理再试）")
                return@launch
            }
            val current = core.installedVersion().ifBlank { CoreManager.VERSION_TAG }
            if (CoreUpdater.isNewer(rel.version, current)) {
                _state.update { it.copy(updateVersion = rel.version, updateUrl = rel.url) }
                toast("发现新内核 " + rel.version)
            } else {
                _state.update { it.copy(updateVersion = "", updateUrl = "") }
                toast("已是最新版本 " + current)
            }
        }
    }

    fun updateCore() {
        val url = _state.value.updateUrl
        val version = _state.value.updateVersion
        if (url.isBlank() || version.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(updating = true, updateProgress = 0) }
            try {
                val gz = CoreUpdater.download(url, _state.value.coreRunning) { p ->
                    _state.update { it.copy(updateProgress = p) }
                }
                if (gz == null) {
                    toast("下载失败，请检查网络或先开启代理")
                    return@launch
                }
                toast("正在安装 " + version)
                if (!core.installBinaryGz(gz, version)) {
                    toast("安装失败（写入内核失败）")
                    return@launch
                }
                core.stop()
                delay(400)
                if (startEngine()) {
                    _state.update { it.copy(updateVersion = "", updateUrl = "") }
                    toast("内核已更新到 " + version)
                } else {
                    toast("内核已替换，但启动失败，请查看日志")
                }
            } finally {
                _state.update { it.copy(updating = false) }
            }
        }
    }

    // ------------------------------------------------------------ ad sources

    fun addAdSource(url: String, behavior: String, format: String) {
        val u = url.trim()
        if (!u.startsWith("http")) { toast("请输入 http(s) 规则地址"); return }
        val s = store.settings
        val name = "规则 " + (s.adSources.size + 1)
        val ns = s.copy(adSources = s.adSources + com.mihomo.tproxy.model.RuleSource(name, u, behavior, format))
        store.settings = ns
        _state.update { it.copy(settings = ns) }
        toast("已添加规则源，重载后生效")
        applySettings(ns)
    }

    fun removeAdSource(index: Int) {
        val s = store.settings
        if (index !in s.adSources.indices) return
        val ns = s.copy(adSources = s.adSources.filterIndexed { i, _ -> i != index })
        store.settings = ns
        _state.update { it.copy(settings = ns) }
        applySettings(ns)
    }

    // ------------------------------------------------------------ status

    private fun refreshStatus(light: Boolean = false) {
        val running = core.status()
        val version = if (running) api.version() ?: "" else ""
        val mode = if (running) (api.configs()?.optString("mode") ?: "rule") else "rule"
        val paused = running && mode == "direct"
        if (running && !_state.value.coreRunning) {
            _state.update { it.copy(startedAt = System.currentTimeMillis()) }
            startStreams()
            refreshProxies()
        }
        if (!running) {
            trafficJob?.cancel(); logJob?.cancel()
            _state.update { it.copy(upSpeed = 0, downSpeed = 0) }
        }
        _state.update {
            it.copy(
                coreRunning = running,
                version = version,
                mode = mode,
                paused = paused,
                settings = store.settings,
                profiles = store.profiles,
                activeId = store.activeId
            )
        }
        if (!light && running) refreshProxies()
    }

    // ------------------------------------------------------------ engine

    private fun resolveUids(packages: Set<String>): Set<Int> {
        val pm = getApplication<Application>().packageManager
        return packages.mapNotNull { p ->
            runCatching { pm.getApplicationInfo(p, 0).uid }.getOrNull()
        }.toSet()
    }

    private fun composeActive(): String {
        val raw = store.settings
        val s = if (raw.perAppEnabled) raw.copy(perAppUids = resolveUids(raw.perAppPackages)) else raw
        val p = store.profile(store.activeId)
        return when {
            p == null -> ConfigComposer.defaultConfig(s)
            p.isMerge -> ConfigComposer.merge(store.profiles.filter { !it.isMerge && it.content.isNotBlank() }.map { it.content }, s)
            else -> ConfigComposer.compose(p.content, s)
        }
    }

    private fun startEngine(): Boolean {
        if (!core.status()) {
            val yaml = composeActive()
            if (!writeValidated(yaml)) return false
            val r = core.start()
            if (!r.ok) {
                toast("启动失败: " + r.message)
                return false
            }
            var ok = false
            for (i in 0 until 40) {
                if (api.version() != null) { ok = true; break }
                Thread.sleep(120)
            }
            if (!ok) {
                toast("内核未响应，请查看日志")
                return false
            }
            api.setMode(store.settings.mode)
            _state.update { it.copy(startedAt = System.currentTimeMillis()) }
            startStreams()
            refreshProxies()
            return true
        }
        return true
    }

    fun toggleProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                if (!core.status()) {
                    if (startEngine()) toast("代理已开启")
                } else {
                    val paused = _state.value.paused
                    val modeCfg = if (store.settings.mode == "direct") "rule" else store.settings.mode
                    val target = if (paused) modeCfg else "direct"
                    api.setMode(target)
                    toast(if (paused) "代理已恢复" else "代理已暂停（内核仍在运行）")
                }
                refreshStatus(light = true)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun stopCore() {
        viewModelScope.launch(Dispatchers.IO) {
            core.stop()
            _state.update { it.copy(coreRunning = false, upSpeed = 0, downSpeed = 0, nodes = emptyList(), groups = emptyList()) }
            toast("内核已停止")
        }
    }

    fun restartCore() {
        viewModelScope.launch(Dispatchers.IO) {
            core.stop()
            delay(300)
            if (startEngine()) toast("已重启") else toast("重启失败")
            refreshStatus()
        }
    }

    fun setMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var s = store.settings
            s = s.copy(mode = mode)
            store.settings = s
            if (core.status()) api.setMode(mode)
            _state.update { it.copy(mode = mode, settings = s, paused = false) }
            toast(
                when (mode) {
                    "global" -> "全局代理"
                    "direct" -> "全局直连"
                    else -> "规则模式"
                }
            )
        }
    }

    /** Compare the installed app version with the latest GitHub release. */
    fun checkAppUpdate() {
        toast("正在检查更新…")
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                val current = com.mihomo.tproxy.BuildConfig.VERSION_NAME
                // API first (gives notes + apk link), then plain redirect as a fallback
                val rel = CoreUpdater.latestRelease("De4869/tunclash", true)
                    ?: CoreUpdater.latestRelease("De4869/tunclash", false)
                if (rel != null) {
                    val latest = rel.tag.trim().removePrefix("v")
                    if (latest == current) {
                        _state.update { it.copy(appUpdateChecked = true, appUpdateLatest = current, appUpdateVersion = "", appUpdateError = "") }
                        toast("已是最新版本 v" + current)
                    } else {
                        _state.update {
                            it.copy(
                                appUpdateChecked = true,
                                appUpdateLatest = "",
                                appUpdateVersion = latest,
                                appUpdateNotes = rel.notes,
                                appUpdateUrl = rel.apkUrl,
                                appUpdateError = ""
                            )
                        }
                    }
                    return@launch
                }
                val tag = CoreUpdater.tagFromRedirect("De4869/tunclash", true)
                    ?: CoreUpdater.tagFromRedirect("De4869/tunclash", false)
                if (tag != null) {
                    val latest = tag.trim().removePrefix("v")
                    if (latest == current) {
                        _state.update { it.copy(appUpdateChecked = true, appUpdateLatest = current, appUpdateVersion = "", appUpdateError = "") }
                        toast("已是最新版本 v" + current)
                    } else {
                        _state.update {
                            it.copy(
                                appUpdateChecked = true,
                                appUpdateLatest = "",
                                appUpdateVersion = latest,
                                appUpdateNotes = "",
                                appUpdateUrl = "",
                                appUpdateError = ""
                            )
                        }
                    }
                } else {
                    _state.update { it.copy(appUpdateChecked = true, appUpdateError = "检查失败，请检查网络") }
                    toast("检查更新失败，请检查网络后重试")
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissAppUpdate() {
        _state.update { it.copy(appUpdateVersion = "") }
    }

    /** Manually refresh every online ad-rule source, and remember how many entries each one has. */
    fun updateAdRules() {
        val s0 = store.settings
        if (s0.adSources.isEmpty()) { toast("还没有在线规则源"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                var ok = 0
                val list = s0.adSources.map { src ->
                    val text = runCatching {
                        SubFetcher.fetch(src.url, 25000, store.settings.skipTlsVerify).content
                    }.getOrNull()
                    if (text == null) src else {
                        ok++
                        src.copy(count = countRuleEntries(text, src.format), updatedAt = System.currentTimeMillis())
                    }
                }
                val ns = s0.copy(adSources = list)
                store.settings = ns
                _state.update { it.copy(settings = ns) }
                if (core.status()) applySettings(ns)
                val total = list.filter { it.count > 0 }.sumOf { it.count }
                toast("规则已更新 " + ok + "/" + list.size + " 个源 · 共 " + total + " 条")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun countRuleEntries(text: String, format: String): Int {
        if (format.equals("mrs", true)) return -1
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val p = lines.indexOfFirst { it.startsWith("payload:") }
        val body = if (p >= 0) lines.drop(p + 1) else lines
        return when (format.lowercase()) {
            "yaml" -> body.count { it.startsWith("-") }        // payload 列表
            else -> body.count { !it.startsWith("#") }          // classical 文本：一行一条
        }
    }

    /** Theme only affects the UI, no config reload needed. */
    fun setTheme(mode: String) {
        val s = store.settings.copy(themeMode = mode)
        store.settings = s
        _state.update { it.copy(settings = s) }
    }

    /** Subscription-only switch: applied on the next download, the running core is untouched. */
    fun setSkipTlsVerify(enabled: Boolean) {
        val s = store.settings.copy(skipTlsVerify = enabled)
        store.settings = s
        _state.update { it.copy(settings = s) }
        if (enabled) {
            toast("已开启：订阅下载将跳过证书校验")
        } else {
            toast("已恢复订阅证书校验")
        }
    }

    fun applySettings(s: ProxySettings) {
        viewModelScope.launch(Dispatchers.IO) {
            store.settings = s
            _state.update { it.copy(settings = s) }
            if (core.status()) {
                val yaml = composeActive()
                if (writeValidated(yaml)) {
                    val ok = api.reloadConfig(CoreManager.CONFIG)
                    toast(if (ok) "配置已热重载" else "配置已写入，重启内核后生效")
                }
            }
        }
    }

    fun setBootStart(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var s = store.settings
            s = s.copy(bootStart = enabled)
            store.settings = s
            core.setBootStart(enabled, s)
            _state.update { it.copy(settings = s) }
            toast(if (enabled) "已设置开机自启" else "已关闭开机自启")
        }
    }

    // ------------------------------------------------------------ proxies

    fun refreshProxies() {
        val j = api.proxies()?.optJSONObject("proxies") ?: return
        val groups = mutableListOf<GroupInfo>()
        val nodes = mutableListOf<NodeInfo>()
        val it = j.keys()
        while (it.hasNext()) {
            val name = it.next()
            val o = j.optJSONObject(name) ?: continue
            val type = o.optString("type")
            val all = o.optJSONArray("all")
            if (all != null) {
                val members = (0 until all.length()).map { i -> all.optString(i) }
                groups += GroupInfo(name = name, type = type, now = o.optString("now"), nodes = members)
            } else {
                val history = o.optJSONArray("history")
                var d = 0
                var alive = o.optBoolean("alive", true)
                if (history != null && history.length() > 0) {
                    val last = history.optJSONObject(history.length() - 1)
                    d = last?.optInt("delay", 0) ?: 0
                    alive = d > 0
                }
                nodes += NodeInfo(name = name, type = type, delay = d, alive = alive, udp = o.optBoolean("udp", false))
            }
        }
        val order = { g: GroupInfo ->
            when {
                g.name == ConfigComposer.TARGET -> 0
                g.name == "GLOBAL" -> 9
                g.type == "Selector" -> 1
                g.name == ConfigComposer.AUTO -> 2
                else -> 3
            }
        }
        _state.update { it.copy(groups = groups.sortedBy(order), nodes = nodes) }
        // the merged profile card should show what is actually loaded, not a stale YAML count
        val active = store.profile(store.activeId)
        if (active?.isMerge == true && active.nodeCount != nodes.size) {
            store.upsert(active.copy(nodeCount = nodes.size))
            _state.update { it.copy(profiles = store.profiles) }
        }
    }

    fun select(group: String, node: String) {
        viewModelScope.launch(Dispatchers.IO) {
            api.select(group, node)
            delay(120)
            refreshProxies()
            toast("已切换: " + node)
        }
    }

    fun speedTestAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                val group = _state.value.groups.firstOrNull { it.name == ConfigComposer.TARGET }
                    ?: _state.value.groups.firstOrNull { it.type == "Selector" }
                if (group == null) {
                    toast("还没有可测速的策略组")
                    return@launch
                }
                // mihomo tests the whole group in parallel, one round trip
                val res = api.groupDelay(group.name, timeoutMs = 3000)
                if (res == null) {
                    toast("测速失败，请确认内核在运行")
                    return@launch
                }
                val delays = HashMap<String, Int>()
                val it2 = res.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    delays[k] = res.optInt(k, 0)
                }
                _state.update { st ->
                    st.copy(nodes = st.nodes.map { n ->
                        val d = delays[n.name]
                        if (d == null) n else n.copy(delay = d, alive = d > 0)
                    })
                }
                val best = delays.filter { it.value > 0 && !ConfigComposer.isJunkNode(it.key) }.minByOrNull { it.value }
                if (best != null) {
                    api.select(group.name, best.key)
                    delay(200)
                    refreshProxies()
                    toast("已自动选择最快节点：" + best.key + " · " + best.value + "ms")
                } else {
                    toast("全部节点超时，请检查订阅或网络")
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun autoSelect() {
        val g = _state.value.groups.firstOrNull { it.name == ConfigComposer.TARGET } ?: return
        select(g.name, ConfigComposer.AUTO)
    }

    // ------------------------------------------------------------ profiles

    private fun doneOnMain(done: (Boolean, String) -> Unit, ok: Boolean, name: String) {
        viewModelScope.launch(Dispatchers.Main) { done(ok, name) }
    }

    fun addProfile(raw: String, done: (Boolean, String) -> Unit = { _, _ -> }) {
        val text = raw.trim()
        if (text.isBlank()) { doneOnMain(done, false, "内容为空"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                val isUrl = (text.startsWith("http://") || text.startsWith("https://")) && !text.contains("\n")
                if (isUrl) {
                    val r = runCatching { SubFetcher.fetch(text, insecure = store.settings.skipTlsVerify) }
                    if (r.isFailure) {
                        toast("订阅下载失败: " + (r.exceptionOrNull()?.message ?: ""))
                        doneOnMain(done, false, "下载失败")
                        return@launch
                    }
                    val sub = r.getOrThrow()
                    val content = sub.content
                    val p = Profile(
                        id = store.newId(),
                        name = sub.title.ifBlank { "订阅 " + (store.profiles.size + 1) },
                        kind = "url",
                        url = text,
                        content = content,
                        updatedAt = System.currentTimeMillis(),
                        upload = sub.upload, download = sub.download,
                        total = sub.total, expire = sub.expire,
                        nodeCount = ConfigComposer.nodeCount(content)
                    )
                    store.upsert(p)
                    _state.update { it.copy(profiles = store.profiles) }
                    if (store.activeId.isBlank()) activate(p.id)
                    toast("已添加: " + p.name + " · " + p.nodeCount + " 节点")
                    doneOnMain(done, true, p.name)
                } else {
                    val content = text
                    val p = Profile(
                        id = store.newId(),
                        name = "本地配置 " + (store.profiles.size + 1),
                        kind = if (content.contains("proxies:")) "yaml" else "links",
                        content = content,
                        updatedAt = System.currentTimeMillis(),
                        nodeCount = ConfigComposer.nodeCount(content)
                    )
                    val looksLikeConfig = content.contains("proxies:") || content.contains("proxy-providers:") ||
                        content.contains("proxy-groups:") || content.contains("rules:")
                    if (p.nodeCount == 0 && !looksLikeConfig) {
                        toast("未识别到节点，请检查内容")
                        doneOnMain(done, false, "无节点")
                        return@launch
                    }
                    store.upsert(p)
                    _state.update { it.copy(profiles = store.profiles) }
                    if (store.activeId.isBlank()) activate(p.id)
                    toast("已添加 " + p.nodeCount + " 个节点")
                    done(true, p.name)
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun updateProfile(id: String) {
        val p = store.profile(id) ?: return
        if (p.url.isBlank()) { toast("本地配置无需更新"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                val r = runCatching { SubFetcher.fetch(p.url, insecure = store.settings.skipTlsVerify) }
                if (r.isFailure) { toast("更新失败"); return@launch }
                val sub = r.getOrThrow()
                val np = p.copy(
                    content = sub.content,
                    updatedAt = System.currentTimeMillis(),
                    upload = sub.upload, download = sub.download,
                    total = sub.total, expire = sub.expire,
                    nodeCount = ConfigComposer.nodeCount(sub.content)
                )
                store.upsert(np)
                _state.update { it.copy(profiles = store.profiles) }
                if (store.activeId == id) applySettings(store.settings)
                toast("已更新: " + np.nodeCount + " 节点")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun updateAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                var ok = 0
                store.profiles.filter { it.kind == "url" && it.url.isNotBlank() }.forEach { p ->
                    val r = runCatching { SubFetcher.fetch(p.url, insecure = store.settings.skipTlsVerify) }
                    if (r.isSuccess) {
                        val sub = r.getOrThrow()
                        store.upsert(
                            p.copy(
                                content = sub.content,
                                updatedAt = System.currentTimeMillis(),
                                upload = sub.upload, download = sub.download,
                                total = sub.total, expire = sub.expire,
                                nodeCount = ConfigComposer.nodeCount(sub.content)
                            )
                        )
                        ok++
                    }
                }
                _state.update { it.copy(profiles = store.profiles) }
                // keep the merged profile in sync, otherwise its node list/count stays stale
                refreshMerge()
                if (store.activeId.isNotBlank()) applySettings(store.settings)
                toast("已更新 " + ok + " 个订阅" + (if (store.profiles.any { it.isMerge }) "，聚合已同步" else ""))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Recompute the merged profile from the current subscriptions (no activation). */
    private fun refreshMerge() {
        val existing = store.profiles.firstOrNull { it.isMerge } ?: return
        val sources = store.profiles.filter { !it.isMerge && it.content.isNotBlank() }
        if (sources.isEmpty()) return
        val yaml = ConfigComposer.merge(sources.map { it.content }, store.settings)
        val count = ConfigComposer.nodeCount(yaml)
        store.upsert(
            existing.copy(
                name = "聚合节点 · " + sources.size + " 个订阅",
                updatedAt = System.currentTimeMillis(),
                nodeCount = count
            )
        )
        _state.update { it.copy(profiles = store.profiles) }
    }

    fun activate(id: String) {
        store.activeId = id
        _state.update { it.copy(activeId = id, profiles = store.profiles, busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = store.profile(id)?.name ?: ""
                if (!core.status()) {
                    if (startEngine()) toast("已切换到「" + name + "」") else toast("启动失败，请查看日志")
                    return@launch
                }
                val yaml = composeActive()
                if (!writeValidated(yaml)) {
                    toast("切换失败：新配置未通过校验，已保留原配置")
                    return@launch
                }
                val reloaded = api.reloadConfig(CoreManager.CONFIG)
                delay(600)
                if (!reloaded) {
                    // hot reload refused: restart the process so the new config is definitely loaded
                    core.stop()
                    delay(400)
                    if (!startEngine()) {
                        toast("切换失败：内核重启失败")
                        return@launch
                    }
                }
                api.setMode(store.settings.mode)
                delay(300)
                refreshProxies()
                _state.update { it.copy(mode = store.settings.mode, paused = false) }
                toast("已切换到「" + name + "」· " + _state.value.nodes.size + " 个节点")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun deleteProfile(id: String) {
        store.remove(id)
        _state.update { it.copy(profiles = store.profiles, activeId = store.activeId) }
        if (store.activeId.isBlank()) stopCore()
    }

    fun mergeAll() {
        val sources = store.profiles.filter { !it.isMerge && it.content.isNotBlank() }
        if (sources.size < 1) { toast("没有可整合的订阅"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                val yaml = ConfigComposer.merge(sources.map { it.content }, store.settings)
                val count = ConfigComposer.nodeCount(yaml)
                val existing = store.profiles.firstOrNull { it.isMerge }
                val p = Profile(
                    id = existing?.id ?: store.newId(),
                    name = "聚合节点 · " + sources.size + " 个订阅",
                    kind = "merge",
                    content = "",
                    updatedAt = System.currentTimeMillis(),
                    nodeCount = count
                )
                store.upsert(p)
                _state.update { it.copy(profiles = store.profiles) }
                activate(p.id)
                toast("整合完成：" + sources.size + " 个订阅，去重后 " + count + " 个节点")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ------------------------------------------------------------ streams

    private fun startStreams() {
        if (trafficJob?.isActive != true) {
            trafficJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (core.status()) {
                        api.stream("/traffic") { line ->
                            runCatching {
                                val o = JSONObject(line)
                                val up = o.optLong("up", 0)
                                val down = o.optLong("down", 0)
                                _state.update {
                                    it.copy(
                                        upSpeed = up, downSpeed = down,
                                        upTotal = it.upTotal + up,
                                        downTotal = it.downTotal + down
                                    )
                                }
                            }
                            true
                        }
                    }
                    delay(1500)
                }
            }
        }
        if (logJob?.isActive != true) {
            logJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (core.status()) {
                        api.stream("/logs?level=" + store.settings.logLevel) { line ->
                            runCatching {
                                val o = JSONObject(line)
                                val payload = o.optString("payload")
                                if (payload.isNotBlank()) {
                                    val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                        .format(java.util.Date())
                                    _state.update { it.copy(logs = (it.logs + (t + "  " + payload)).takeLast(400)) }
                                }
                            }
                            true
                        }
                    }
                    delay(1500)
                }
            }
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun toast(msg: String) {
        _state.update { it.copy(toast = msg) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshStatus()
            refreshProxies()
        }
    }
}