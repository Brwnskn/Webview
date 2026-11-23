package com.example

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Minimal key-value persistence abstraction so [SitePointerStore] and [SiteStructSchemaStore]
 * can run synchronously on Android [SharedPreferences] in production and on a pure in-memory
 * map in fast JVM unit tests.
 */
interface PointerKeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun allKeys(): Set<String>
}

/**
 * Synchronous [SharedPreferences] implementation using `commit()` so bookmarked pointers and
 * struct schemas are flushed to disk immediately and survive immediate app termination.
 */
class SharedPrefsPointerStorage(
    private val prefs: SharedPreferences
) : PointerKeyValueStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun allKeys(): Set<String> = prefs.all.keys.toSet()
}

class InMemoryPointerStorage : PointerKeyValueStorage {
    private val map = LinkedHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun allKeys(): Set<String> = map.keys.toSet()
}

data class SavedSitePointersRecord(
    val hostKey: String,
    val lastUrl: String,
    val pointers: List<WasmPointerChain>,
    val updatedAtMs: Long = 0L
)

/**
 * Private on-device per-site persistence store for bookmarked WASM pointer chains.
 *
 * Keyed by normalized host (e.g. `universe.flyff.com`) with registrable root-domain fallback
 * matching (e.g. `play.universe.flyff.com` ↔ `universe.flyff.com`) and synchronous disk writes
 * so saved pointer chains survive scanner Reset, WebView reloads, and cold app restarts.
 */
class SitePointerStore(
    private val storage: PointerKeyValueStorage
) {
    constructor(context: Context) : this(
        SharedPrefsPointerStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    /**
     * Resolves the best stored host key for [url]:
     * 1. Exact host match if pointers exist for that host.
     * 2. Fallback to another saved host sharing the same registrable root domain.
     * 3. Otherwise returns the normalized host of [url].
     */
    fun resolveStoredHostKeyForUrl(url: String): String? {
        val exactHost = normalizeHostKey(url) ?: return null
        if (!storage.getString(prefKeyForHost(exactHost)).isNullOrBlank()) {
            return exactHost
        }
        val targetDomain = extractRegistrableDomain(exactHost) ?: return exactHost
        val fallbackHost = listAllStoredHosts().firstOrNull { savedHost ->
            savedHost != exactHost &&
                extractRegistrableDomain(savedHost) == targetDomain &&
                !storage.getString(prefKeyForHost(savedHost)).isNullOrBlank()
        }
        return fallbackHost ?: exactHost
    }

    fun loadPointersForUrl(url: String): List<WasmPointerChain> {
        val resolvedHost = resolveStoredHostKeyForUrl(url) ?: return emptyList()
        return loadPointersForHost(resolvedHost)
    }

    fun loadPointersForHost(hostKey: String): List<WasmPointerChain> {
        val normalized = normalizeHostKey(hostKey) ?: return emptyList()
        val raw = storage.getString(prefKeyForHost(normalized)) ?: return emptyList()
        return deserializeChains(raw)
    }

    fun savePointerForUrl(url: String, chain: WasmPointerChain): List<WasmPointerChain> {
        val exactHost = normalizeHostKey(url) ?: return emptyList()
        val existing = loadPointersForHost(exactHost).toMutableList()
        val savedEntry = chain.copy(
            isPinned = true,
            isSavedLocally = true
        )
        val idx = existing.indexOfFirst { it.id == savedEntry.id }
        if (idx >= 0) {
            existing[idx] = savedEntry
        } else {
            existing.add(0, savedEntry)
        }
        val bounded = existing.take(MAX_SAVED_PER_HOST)
        storage.putString(prefKeyForHost(exactHost), serializeChains(bounded))
        saveHostMeta(exactHost, canonicalUrl(url, exactHost), System.currentTimeMillis())
        return bounded
    }

    fun removePointerForUrl(url: String, chainId: String): List<WasmPointerChain> {
        val resolvedHost = resolveStoredHostKeyForUrl(url) ?: return emptyList()
        return removePointerForHost(resolvedHost, chainId)
    }

    fun removePointerForHost(hostKey: String, chainId: String): List<WasmPointerChain> {
        val normalized = normalizeHostKey(hostKey) ?: return emptyList()
        val updated = loadPointersForHost(normalized).filterNot { it.id == chainId }
        if (updated.isEmpty()) {
            storage.remove(prefKeyForHost(normalized))
            removeHostMeta(normalized)
        } else {
            storage.putString(prefKeyForHost(normalized), serializeChains(updated))
        }
        return updated
    }

    fun clearPointersForUrl(url: String) {
        val exactHost = normalizeHostKey(url) ?: return
        clearPointersForHost(exactHost)
    }

    fun clearPointersForHost(hostKey: String) {
        val normalized = normalizeHostKey(hostKey) ?: return
        storage.remove(prefKeyForHost(normalized))
        removeHostMeta(normalized)
    }

    fun countSavedForUrl(url: String): Int = loadPointersForUrl(url).size

    /**
     * Lists all saved site pointer records on disk (most recently saved first),
     * scanning both the metadata index and any legacy `site_ptrs_*` entries.
     */
    fun listSavedSites(): List<SavedSitePointersRecord> {
        val hosts = listAllStoredHosts()
        val records = buildList {
            hosts.forEach { host ->
                val pointers = loadPointersForHost(host)
                if (pointers.isNotEmpty()) {
                    val meta = readHostMeta(host)
                    val url = meta?.optString("lastUrl", "")?.takeIf { it.isNotBlank() }
                        ?: "https://$host"
                    val updatedAt = meta?.optLong("updatedAtMs", 0L) ?: 0L
                    add(
                        SavedSitePointersRecord(
                            hostKey = host,
                            lastUrl = url,
                            pointers = pointers,
                            updatedAtMs = updatedAt
                        )
                    )
                }
            }
        }
        return records.sortedWith(
            compareByDescending<SavedSitePointersRecord> { it.updatedAtMs }
                .thenBy { it.hostKey }
        )
    }

    private fun listAllStoredHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        // 1. Scan persisted index
        val indexRaw = storage.getString(KEY_HOST_INDEX)
        if (!indexRaw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(indexRaw)
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i, "").trim().lowercase(Locale.US)
                    if (h.isNotEmpty()) hosts.add(h)
                }
            }
        }
        // 2. Scan all SharedPreferences keys so legacy entries without an index are still discovered
        storage.allKeys().forEach { key ->
            if (key.startsWith(KEY_PREFIX)) {
                val h = key.removePrefix(KEY_PREFIX).trim().lowercase(Locale.US)
                if (h.isNotEmpty()) hosts.add(h)
            }
        }
        return hosts.toList()
    }

    private fun saveHostMeta(hostKey: String, lastUrl: String, updatedAtMs: Long) {
        val metaJson = JSONObject()
            .put("lastUrl", lastUrl)
            .put("updatedAtMs", updatedAtMs)
            .toString()
        storage.putString(metaKeyForHost(hostKey), metaJson)

        val hosts = LinkedHashSet<String>(listAllStoredHosts())
        hosts.add(hostKey)
        val arr = JSONArray()
        hosts.forEach { arr.put(it) }
        storage.putString(KEY_HOST_INDEX, arr.toString())
    }

    private fun readHostMeta(hostKey: String): JSONObject? {
        val raw = storage.getString(metaKeyForHost(hostKey)) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun removeHostMeta(hostKey: String) {
        storage.remove(metaKeyForHost(hostKey))
        val hosts = listAllStoredHosts().filterNot { it == hostKey }
        if (hosts.isEmpty()) {
            storage.remove(KEY_HOST_INDEX)
        } else {
            val arr = JSONArray()
            hosts.forEach { arr.put(it) }
            storage.putString(KEY_HOST_INDEX, arr.toString())
        }
    }

    companion object {
        private const val PREFS_NAME = "webview_diagnostics_site_pointers"
        private const val KEY_PREFIX = "site_ptrs_"
        private const val META_PREFIX = "site_meta_"
        private const val KEY_HOST_INDEX = "site_host_index_v1"
        private const val MAX_SAVED_PER_HOST = 32

        fun normalizeHostKey(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) return null
            val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val host = runCatching { URI(candidate).host }.getOrNull()?.trim()?.lowercase(Locale.US)
            if (host.isNullOrBlank()) return null
            return host
        }

        /**
         * Extracts the registrable root domain (e.g. `play.universe.flyff.com` -> `flyff.com`)
         * for subdomain fallback matching. Returns null for localhost or numeric IP addresses.
         */
        fun extractRegistrableDomain(hostKey: String): String? {
            val clean = hostKey.trim().lowercase(Locale.US).trimEnd('.')
            if (clean.isEmpty() || clean == "localhost") return null
            val parts = clean.split('.').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            if (parts.all { part -> part.all { it.isDigit() } }) return null
            val multiPartSuffixes = setOf("co.uk", "com.au", "co.jp", "com.br", "co.kr", "org.uk", "net.au")
            val lastTwo = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
            return if (parts.size >= 3 && multiPartSuffixes.contains(lastTwo)) {
                "${parts[parts.size - 3]}.$lastTwo"
            } else {
                lastTwo
            }
        }

        private fun canonicalUrl(rawUrl: String, hostKey: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                return trimmed
            }
            return "https://$hostKey"
        }

        private fun prefKeyForHost(hostKey: String): String = "$KEY_PREFIX$hostKey"
        private fun metaKeyForHost(hostKey: String): String = "$META_PREFIX$hostKey"

        fun serializeChains(chains: List<WasmPointerChain>): String {
            val arr = JSONArray()
            chains.forEach { c ->
                val offs = JSONArray()
                c.offsets.forEach { offs.put(it) }
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("depth", c.depth)
                        .put("basePointerAddress", c.basePointerAddress)
                        .put("offsets", offs)
                        .put("resolvedTargetAddress", c.resolvedTargetAddress)
                        .put("resolvedX", c.resolvedX)
                        .put("resolvedY", c.resolvedY)
                        .put("resolvedZ", c.resolvedZ)
                        .put("isLikelyStatic", c.isLikelyStatic)
                        .put("expression", c.expression)
                )
            }
            return arr.toString()
        }

        fun deserializeChains(rawJson: String): List<WasmPointerChain> {
            val arr = try {
                JSONArray(rawJson)
            } catch (_: Exception) {
                return emptyList()
            }
            return buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    val depth = obj.optInt("depth", 1).coerceIn(1, 4)
                    val baseAddr = obj.optLong("basePointerAddress", 0L)
                    if (baseAddr <= 0L) continue
                    val offArr = obj.optJSONArray("offsets")
                    val offsets = buildList {
                        if (offArr != null) {
                            for (j in 0 until offArr.length()) {
                                add(offArr.optInt(j, 0))
                            }
                        }
                    }
                    val expression = obj.optString("expression", "").ifBlank {
                        val hexBase = DiagnosticsViewModel.formatHexAddress(baseAddr)
                        if (offsets.size >= 2) {
                            "*(*($hexBase) + 0x${offsets[0].toString(16).uppercase(Locale.US)}) + 0x${offsets[1].toString(16).uppercase(Locale.US)}"
                        } else {
                            val off = offsets.firstOrNull() ?: 0
                            "*($hexBase) + 0x${off.toString(16).uppercase(Locale.US)}"
                        }
                    }
                    add(
                        WasmPointerChain(
                            id = id,
                            depth = depth,
                            basePointerAddress = baseAddr,
                            offsets = offsets,
                            resolvedTargetAddress = obj.optLong("resolvedTargetAddress", 0L),
                            resolvedX = obj.optDouble("resolvedX", 0.0),
                            resolvedY = obj.optDouble("resolvedY", 0.0),
                            resolvedZ = obj.optDouble("resolvedZ", 0.0),
                            isLikelyStatic = obj.optBoolean("isLikelyStatic", false),
                            isVerifiedLive = false,
                            expression = expression,
                            isPinned = true,
                            status = WasmPointerStatus.PENDING,
                            isSavedLocally = true
                        )
                    )
                }
            }
        }
    }
}
