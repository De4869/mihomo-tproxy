package com.mihomo.tproxy.data

import android.content.Context
import com.mihomo.tproxy.model.Profile
import com.mihomo.tproxy.model.RuleSource
import com.mihomo.tproxy.model.ProxySettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Store(ctx: Context) {

    private val sp = ctx.getSharedPreferences("mihomo_store", Context.MODE_PRIVATE)

    var settings: ProxySettings
        get() {
            val j = runCatching { JSONObject(sp.getString("settings", "{}")!!) }.getOrNull() ?: JSONObject()
            return ProxySettings(
                themeMode = j.optString("themeMode", "system"),
                mode = j.optString("mode", "rule"),
                rulePreset = j.optString("rulePreset", "smart"),
                dnsPreset = j.optString("dnsPreset", "cn"),
                customDns = j.optString("customDns", ""),
                ipv6 = j.optBoolean("ipv6", false),
                tunStack = j.optString("tunStack", "mixed"),
                mtu = j.optInt("mtu", 9000),
                logLevel = j.optString("logLevel", "info"),
                perAppEnabled = j.optBoolean("perAppEnabled", false),
                perAppWhitelist = j.optBoolean("perAppWhitelist", false),
                perAppPackages = j.optJSONArray("perAppPackages")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.toSet()
                } ?: emptySet(),
                adBlock = j.optBoolean("adBlock", false),
                adUpdateHours = j.optInt("adUpdateHours", 24),
                mergeFilter = j.optString("mergeFilter", ""),
                skipTlsVerify = j.optBoolean("skipTlsVerify", false),
                adSources = j.optJSONArray("adSources")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val o = a.optJSONObject(i) ?: return@mapNotNull null
                        RuleSource(
                            o.optString("name"),
                            o.optString("url"),
                            o.optString("behavior", "domain"),
                            o.optString("format", "yaml"),
                            o.optInt("count", -1),
                            o.optLong("updatedAt", 0L)
                        )
                    }
                } ?: emptyList(),
                bootStart = j.optBoolean("bootStart", false),
                secret = secret
            )
        }
        set(v) {
            val j = JSONObject()
            j.put("themeMode", v.themeMode)
            j.put("mode", v.mode)
            j.put("rulePreset", v.rulePreset)
            j.put("dnsPreset", v.dnsPreset)
            j.put("customDns", v.customDns)
            j.put("ipv6", v.ipv6)
            j.put("tunStack", v.tunStack)
            j.put("mtu", v.mtu)
            j.put("logLevel", v.logLevel)
            j.put("perAppEnabled", v.perAppEnabled)
            j.put("perAppWhitelist", v.perAppWhitelist)
            j.put("perAppPackages", JSONArray(v.perAppPackages.toList()))
            j.put("adBlock", v.adBlock)
            j.put("adUpdateHours", v.adUpdateHours)
            j.put("mergeFilter", v.mergeFilter)
            j.put("skipTlsVerify", v.skipTlsVerify)
            j.put("adSources", org.json.JSONArray().apply {
                v.adSources.forEach { s ->
                    put(org.json.JSONObject().apply {
                        put("name", s.name); put("url", s.url)
                        put("behavior", s.behavior); put("format", s.format)
                        put("count", s.count); put("updatedAt", s.updatedAt)
                    })
                }
            })
            j.put("bootStart", v.bootStart)
            sp.edit().putString("settings", j.toString()).apply()
        }

    var secret: String
        get() {
            var s = sp.getString("secret", "") ?: ""
            if (s.isBlank()) {
                s = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                sp.edit().putString("secret", s).apply()
            }
            return s
        }
        set(v) { sp.edit().putString("secret", v).apply() }

    var profiles: List<Profile>
        get() {
            val raw = sp.getString("profiles", "[]") ?: "[]"
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    Profile(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        kind = o.optString("kind"),
                        url = o.optString("url", ""),
                        content = o.optString("content", ""),
                        updatedAt = o.optLong("updatedAt", 0),
                        upload = o.optLong("upload", 0),
                        download = o.optLong("download", 0),
                        total = o.optLong("total", 0),
                        expire = o.optLong("expire", 0),
                        nodeCount = o.optInt("nodeCount", 0)
                    )
                }.getOrNull()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { p ->
                val o = JSONObject()
                o.put("id", p.id); o.put("name", p.name); o.put("kind", p.kind)
                o.put("url", p.url); o.put("content", p.content)
                o.put("updatedAt", p.updatedAt); o.put("upload", p.upload)
                o.put("download", p.download); o.put("total", p.total)
                o.put("expire", p.expire); o.put("nodeCount", p.nodeCount)
                arr.put(o)
            }
            sp.edit().putString("profiles", arr.toString()).apply()
        }

    var activeId: String
        get() = sp.getString("active", "") ?: ""
        set(v) { sp.edit().putString("active", v).apply() }

    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }

    fun upsert(p: Profile) {
        val list = profiles.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        profiles = list
    }

    fun remove(id: String) {
        profiles = profiles.filterNot { it.id == id }
        if (activeId == id) activeId = ""
    }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
}
