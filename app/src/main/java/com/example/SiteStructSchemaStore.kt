package com.example

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Private on-device per-site persistence store for confirmed WASM entity struct field offsets
 * (HP, MaxHP, MP, MaxMP, FP, MaxFP, and Mob/Player Name).
 *
 * Uses synchronous [SharedPrefsPointerStorage] commits and supports registrable root-domain
 * fallback matching (e.g. `play.universe.flyff.com` ↔ `universe.flyff.com`) so bound stat and
 * name offsets survive Reset, WebView page reloads, and full app restarts.
 */
class SiteStructSchemaStore(
    private val storage: PointerKeyValueStorage
) {
    constructor(context: Context) : this(
        SharedPrefsPointerStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    fun loadSchemaForUrl(url: String): WasmEntityStructSchema {
        val exactHost = SitePointerStore.normalizeHostKey(url) ?: return WasmEntityStructSchema()
        val exactRaw = storage.getString(prefKeyForHost(exactHost))
        if (!exactRaw.isNullOrBlank()) {
            return deserializeSchema(exactRaw, exactHost)
        }
        val targetDomain = SitePointerStore.extractRegistrableDomain(exactHost)
            ?: return WasmEntityStructSchema(hostKey = exactHost)
        val fallbackHost = listSavedSchemaHosts().keys.firstOrNull { savedHost ->
            savedHost != exactHost &&
                SitePointerStore.extractRegistrableDomain(savedHost) == targetDomain
        }
        if (fallbackHost != null) {
            val fallbackRaw = storage.getString(prefKeyForHost(fallbackHost))
            if (!fallbackRaw.isNullOrBlank()) {
                return deserializeSchema(fallbackRaw, fallbackHost)
            }
        }
        return WasmEntityStructSchema(hostKey = exactHost)
    }

    fun loadSchemaForHost(hostKey: String): WasmEntityStructSchema {
        val normalized = SitePointerStore.normalizeHostKey(hostKey) ?: return WasmEntityStructSchema()
        val raw = storage.getString(prefKeyForHost(normalized))
            ?: return WasmEntityStructSchema(hostKey = normalized)
        return deserializeSchema(raw, normalized)
    }

    fun saveSchemaForUrl(url: String, schema: WasmEntityStructSchema): WasmEntityStructSchema {
        val hostKey = SitePointerStore.normalizeHostKey(url) ?: return schema
        if (!schema.hasAnyBound) {
            storage.remove(prefKeyForHost(hostKey))
            return WasmEntityStructSchema(hostKey = hostKey, isSavedLocally = false)
        }
        val persisted = schema.copy(isSavedLocally = true, hostKey = hostKey)
        storage.putString(prefKeyForHost(hostKey), serializeSchema(persisted))
        return persisted
    }

    fun clearSchemaForUrl(url: String) {
        val hostKey = SitePointerStore.normalizeHostKey(url) ?: return
        clearSchemaForHost(hostKey)
    }

    fun clearSchemaForHost(hostKey: String) {
        val normalized = SitePointerStore.normalizeHostKey(hostKey) ?: return
        storage.remove(prefKeyForHost(normalized))
    }

    fun listSavedSchemaHosts(): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        storage.allKeys().forEach { key ->
            if (key.startsWith(KEY_PREFIX)) {
                val host = key.removePrefix(KEY_PREFIX).trim().lowercase(Locale.US)
                if (host.isNotEmpty()) {
                    val schema = loadSchemaForHost(host)
                    if (schema.hasAnyBound) {
                        result[host] = schema.boundCount
                    }
                }
            }
        }
        return result
    }

    companion object {
        private const val PREFS_NAME = "webview_diagnostics_site_struct_schema"
        private const val KEY_PREFIX = "site_schema_"

        private fun prefKeyForHost(hostKey: String): String = "$KEY_PREFIX$hostKey"

        fun serializeSchema(schema: WasmEntityStructSchema): String {
            val root = JSONObject()
            WasmStructFieldKind.entries.forEach { kind ->
                val field = schema.fieldFor(kind)
                if (field != null) {
                    val obj = JSONObject()
                        .put("relOffsetBytes", field.relOffsetBytes)
                        .put("valueType", field.valueType.name)
                        .put("expression", field.displayExpression)
                    if (field.subOffsetBytes != null) {
                        obj.put("subOffsetBytes", field.subOffsetBytes)
                    }
                    root.put(kind.jsonKey, obj)
                }
            }
            return root.toString()
        }

        fun deserializeSchema(rawJson: String, hostKey: String = ""): WasmEntityStructSchema {
            val root = try {
                JSONObject(rawJson)
            } catch (_: Exception) {
                return WasmEntityStructSchema(hostKey = hostKey)
            }
            var schema = WasmEntityStructSchema(hostKey = hostKey)
            WasmStructFieldKind.entries.forEach { kind ->
                val obj = root.optJSONObject(kind.jsonKey) ?: return@forEach
                if (!obj.has("relOffsetBytes")) return@forEach
                val rel = obj.optInt("relOffsetBytes", 0)
                val sub = if (obj.has("subOffsetBytes") && !obj.isNull("subOffsetBytes")) {
                    obj.optInt("subOffsetBytes", 0)
                } else {
                    null
                }
                val valueType = WasmStructValueType.fromRaw(obj.optString("valueType", "INT32"))
                val expr = obj.optString("expression", "")
                schema = schema.withField(
                    kind,
                    WasmStructFieldOffset(
                        relOffsetBytes = rel,
                        subOffsetBytes = sub,
                        valueType = valueType,
                        expression = expr
                    )
                )
            }
            return schema.copy(isSavedLocally = schema.hasAnyBound, hostKey = hostKey)
        }
    }
}
