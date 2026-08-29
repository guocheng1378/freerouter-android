package com.eta.freerouter

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun jsonArrayToStrings(a: JSONArray?): List<String> =
    if (a == null) emptyList() else List(a.length()) { a.optString(it) }

data class Provider(
    val id: String,
    val name: String,
    val nameZh: String?,
    val apiBase: String?,
    val credentialEnv: String?,
    val region: String?,
    val tier: String?,
    val status: String?,
    val freeModels: List<String>,
    val wholeCatalogIsFree: Boolean,
    val discoveryUrl: String?,
    val allow: List<String>?,
    val deny: List<String>?
)

object Catalog {
    lateinit var providers: List<Provider>
        private set

    fun load(context: Context) {
        val json = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONArray("providers")
        val list = mutableListOf<Provider>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Provider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    nameZh = o.optString("nameZh").takeIf { it.isNotEmpty() },
                    apiBase = o.optString("apiBase").takeIf { it.isNotEmpty() },
                    credentialEnv = o.optString("credentialEnv").takeIf { it.isNotEmpty() },
                    region = o.optString("region").takeIf { it.isNotEmpty() },
                    tier = o.optString("tier").takeIf { it.isNotEmpty() },
                    status = o.optString("status").takeIf { it.isNotEmpty() },
                    freeModels = jsonArrayToStrings(o.optJSONArray("freeModels")),
                    wholeCatalogIsFree = o.optBoolean("wholeCatalogIsFree", false),
                    discoveryUrl = o.optString("discoveryUrl").takeIf { it.isNotEmpty() },
                    allow = jsonArrayToStrings(o.optJSONArray("allow")),
                    deny = jsonArrayToStrings(o.optJSONArray("deny"))
                )
            )
        }
        providers = list
    }
}

object Keys {
    private const val PREF = "freerouter_keys"
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            context, PREF, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun get(env: String): String? = prefs.getString(env, null)?.takeIf { it.isNotEmpty() }
    fun set(env: String, value: String) = prefs.edit().putString(env, value).apply()
}

object Router {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val health = mutableMapOf<String, MutableMap<String, Boolean>>()

    data class Target(val provider: Provider, val model: String)

    private val discoveryCache = mutableMapOf<String, Pair<Long, List<String>>>()
    private const val DISCOVERY_TTL_MS = 10 * 60 * 1000L

    private fun globMatch(pattern: String, s: String): Boolean {
        val regex = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex\$").matches(s)
    }

    private fun parseModelIds(body: String): List<String> {
        return try {
            val jo = JSONObject(body)
            val list = when {
                jo.has("data") -> jo.getJSONArray("data")
                jo.has("models") -> jo.getJSONArray("models")
                else -> JSONArray()
            }
            val out = mutableListOf<String>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    private fun discoverModels(p: Provider): List<String> {
        val url = p.discoveryUrl ?: return emptyList()
        val env = p.credentialEnv ?: return emptyList()
        val key = Keys.get(env) ?: return emptyList()
        val cached = discoveryCache[p.id]
        if (cached != null && System.currentTimeMillis() - cached.first < DISCOVERY_TTL_MS) {
            return cached.second
        }
        return try {
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $key")
                .get().build()
            val resp = client.newCall(req).execute()
            val ids = parseModelIds(resp.body?.string() ?: "")
            discoveryCache[p.id] = System.currentTimeMillis() to ids
            ids
        } catch (e: Exception) {
            cached?.second ?: emptyList()
        }
    }

    fun candidateModels(p: Provider): List<String> {
        val allow = p.allow
        val deny = p.deny ?: emptyList()
        val discovered = discoverModels(p)
        val base = if (discovered.isNotEmpty()) {
            val s = if (!allow.isNullOrEmpty()) discovered.filter { allow.contains(it) } else discovered
            s.toMutableList()
        } else {
            val fb = p.freeModels.toMutableList()
            if (fb.isEmpty() && !allow.isNullOrEmpty()) fb += allow
            fb
        }
        val filtered = if (p.wholeCatalogIsFree) base
                       else if (deny.isEmpty()) base
                       else base.filter { m -> deny.none { pat -> globMatch(pat, m) } }
        return filtered.distinct()
    }

    fun resolve(modelField: String): List<Target> {
        val parts = modelField.split("/")
        return when {
            modelField == "free-router" -> {
                val pool = mutableListOf<Target>()
                for (p in Catalog.providers) {
                    val key = p.credentialEnv?.let { Keys.get(it) }
                    if (key.isNullOrEmpty()) continue
                    pool += candidateModels(p).map { Target(p, it) }
                }
                pool
            }
            parts.size == 2 && parts[0].endsWith("-free") -> {
                val pid = parts[0].removeSuffix("-free")
                val p = Catalog.providers.find { it.id == pid } ?: return emptyList()
                val key = p.credentialEnv?.let { Keys.get(it) }
                if (key.isNullOrEmpty()) emptyList() else candidateModels(p).map { Target(p, it) }
            }
            parts.size >= 3 && parts[0] == "fr" -> {
                val pid = parts[1]
                val m = parts.drop(2).joinToString("/")
                val p = Catalog.providers.find { it.id == pid } ?: return emptyList()
                listOf(Target(p, m))
            }
            else -> {
                val pool = mutableListOf<Target>()
                for (p in Catalog.providers) {
                    val key = p.credentialEnv?.let { Keys.get(it) }
                    if (key.isNullOrEmpty()) continue
                    if (candidateModels(p).contains(modelField)) pool += Target(p, modelField)
                }
                pool
            }
        }
    }

    fun forward(target: Target, body: ByteArray?): okhttp3.Response {
        val key = Keys.get(target.provider.credentialEnv!!)!!
        val url = "${target.provider.apiBase!!.removeSuffix("/")}/chat/completions"
        val realBody = body?.let { rewriteModel(it, target.model) } ?: ByteArray(0)
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(realBody.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute()
    }

    private fun rewriteModel(body: ByteArray, realModel: String): ByteArray {
        return try {
            val s = String(body, Charsets.UTF_8)
            val jo = JSONObject(s)
            if (jo.has("model")) jo.put("model", realModel)
            jo.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            body
        }
    }

    fun markHealth(providerId: String, model: String, ok: Boolean) {
        health.getOrPut(providerId) { mutableMapOf() }[model] = ok
    }
}
