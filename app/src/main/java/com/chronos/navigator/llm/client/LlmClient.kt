package com.chronos.navigator.llm.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import com.chronos.navigator.llm.LlmRequest
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How a provider is spoken to. */
enum class ProviderKind {
    /** Claude, through Anthropic's own SDK; the only one that searches the web for sources. */
    ANTHROPIC,
    /** Anything that speaks the OpenAI chat-completions API: Gemini, OpenRouter, Groq, OpenAI, … */
    OPENAI_COMPATIBLE
}

/**
 * A starting point for a provider: where it lives, a model to try, its prices.
 * Prices are null where they depend on the model you pick — then you type them.
 */
data class Preset(
    val key: String, val label: String, val kind: ProviderKind, val baseUrl: String,
    val model: String, val priceIn: Double?, val priceOut: Double?, val free: Boolean,
    val keyPage: String, val note: String
)

/** One provider the user set up: its own model, prices and key. */
data class Profile(
    val id: String, val preset: String, val label: String, val kind: ProviderKind,
    val baseUrl: String, val model: String, val priceIn: Double, val priceOut: Double
) {
    val free get() = priceIn == 0.0 && priceOut == 0.0
    val searches get() = kind == ProviderKind.ANTHROPIC
}

/**
 * The only part of the Navigator that touches the network. It sends one
 * request and reports what came back and what it cost; deciding whether to
 * send, and what to make of the answer, happens elsewhere.
 */
object LlmClient {

    /** Claude prices per million tokens (input, output) in USD, from the published price list. */
    val PRICES: Map<String, Pair<Double, Double>> = linkedMapOf(
        "claude-opus-5-5" to (4.0 to 20.0),
        "claude-sonnet-5-5" to (2.0 to 10.0),
        "claude-haiku-4-5" to (1.0 to 5.0),
    )

    val PRESETS = listOf(
        Preset("anthropic", "Anthropic Claude", ProviderKind.ANTHROPIC, "https://api.anthropic.com",
            "claude-opus-5-5", 4.0, 20.0, false, "console.anthropic.com",
            "Paid. The only one here that searches the web and lists its sources."),
        Preset("gemini", "Google Gemini", ProviderKind.OPENAI_COMPATIBLE,
            "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", 0.0, 0.0, true,
            "aistudio.google.com", "Has a free tier with a daily limit."),
        Preset("openrouter", "OpenRouter", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1",
            "", 0.0, 0.0, true, "openrouter.ai/keys",
            "Many models behind one key; those whose name ends in :free cost nothing."),
        Preset("groq", "Groq", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1",
            "llama-3.3-70b-versatile", 0.0, 0.0, true, "console.groq.com/keys",
            "Has a free tier with rate limits; very fast."),
        Preset("openai", "OpenAI", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
            "", null, null, false, "platform.openai.com/api-keys", "Paid: enter the prices of the model you pick."),
        Preset("custom", "Other (OpenAI-compatible)", ProviderKind.OPENAI_COMPATIBLE, "",
            "", null, null, false, "", "Any service that speaks the OpenAI chat API: give its address."),
    )

    fun preset(key: String) = PRESETS.first { it.key == key }

    /** Web search is billed per search: $10 per thousand. */
    const val PER_SEARCH_USD = 0.01
    /** Searches one request may make. */
    const val MAX_SEARCHES = 3L
    const val MAX_TOKENS = 16000L
    /** Output ceiling for other providers; most cap lower than Claude. */
    private const val COMPAT_MAX_TOKENS = 8000

    /** A Claude model answered under a name not in [PRICES] (a fallback): priced at the dearest known. */
    private fun claudePrice(model: String): Pair<Double, Double> =
        PRICES.entries.firstOrNull { model.startsWith(it.key) }?.value ?: PRICES.values.maxBy { it.second }

    fun costUsd(model: String, tokensIn: Long, tokensOut: Long, searches: Long): Double {
        val (pin, pout) = claudePrice(model)
        return tokensIn * pin / 1e6 + tokensOut * pout / 1e6 + searches * PER_SEARCH_USD
    }

    fun costUsd(p: Profile, reply: Reply): Double =
        if (p.kind == ProviderKind.ANTHROPIC) costUsd(reply.model, reply.tokensIn, reply.tokensOut, reply.searches)
        else reply.tokensIn * p.priceIn / 1e6 + reply.tokensOut * p.priceOut / 1e6

    /** A cautious estimate before sending, for the budget check: the prompt plus a typical answer. */
    fun estimateUsd(p: Profile, req: LlmRequest): Double {
        val tokensIn = ((req.system().length + req.user().length) / 3.0).toLong() + 2000
        return if (p.kind == ProviderKind.ANTHROPIC)
            costUsd(p.model, tokensIn, 4000, if (req.webSearch()) MAX_SEARCHES else 0)
        else tokensIn * p.priceIn / 1e6 + 4000 * p.priceOut / 1e6
    }

    data class Reply(
        val text: String, val urls: List<String>, val model: String,
        val tokensIn: Long, val tokensOut: Long, val searches: Long, val stopReason: String?
    ) {
        val costUsd get() = LlmClient.costUsd(model, tokensIn, tokensOut, searches)
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * The request as a provider without web search should get it: told not to
     * cite pages it cannot have read.
     */
    fun adapt(p: Profile, req: LlmRequest): LlmRequest =
        if (p.searches || !req.webSearch()) req
        else LlmRequest(req.task(), req.target(), req.system(),
            req.user() + "\n\nYou cannot search the web in this request: leave `sources` empty.", false)

    /** Blocks; call on the IO thread. Throws on a network or API error. */
    fun send(p: Profile, apiKey: String, req: LlmRequest): Reply =
        if (p.kind == ProviderKind.ANTHROPIC) sendClaude(apiKey, p.model, req) else sendCompat(p, apiKey, req)

    // ---------------------------------------------------------------- Claude

    fun sendClaude(apiKey: String, model: String, req: LlmRequest): Reply = try {
        sendClaude(apiKey, model, req, withFallback = true)
    } catch (e: com.anthropic.errors.BadRequestException) {
        // An account or region without server-side fallback: ask once more without it.
        if ((e.message ?: "").contains("fallback", ignoreCase = true)) sendClaude(apiKey, model, req, withFallback = false)
        else throw e
    }

    private fun sendClaude(apiKey: String, model: String, req: LlmRequest, withFallback: Boolean): Reply {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(req.system())
                .addUserMessage(req.user())
            val current = !model.startsWith("claude-haiku")
            if (req.webSearch()) {
                if (current) b.addTool(WebSearchTool20260209.builder().maxUses(MAX_SEARCHES).build())
                else b.addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).build())
            }
            if (current) {
                // An estimate, not a proof: medium effort is enough and keeps the cost low.
                b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            }
            if (current && withFallback) {
                // If a safety classifier declines, the server retries on a model it routes to itself.
                b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            // Read the reply as plain JSON: the block shapes are stable there, and only
            // text, search results and usage are needed.
            val body = client.messages().withRawResponse().create(b.build()).use { r ->
                if (r.statusCode() !in 200..299) throw IllegalStateException("The API answered ${r.statusCode()}")
                r.body().readBytes().toString(Charsets.UTF_8)
            }
            return parse(JsonParser.parseString(body).asJsonObject, model)
        } finally {
            client.close()
        }
    }

    internal fun parse(json: JsonObject, requested: String): Reply {
        val text = StringBuilder()
        val urls = mutableListOf<String>()
        json.getAsJsonArray("content")?.forEach { el ->
            val block = el.asJsonObject
            when (block.get("type")?.asString) {
                "text" -> text.append(block.get("text")?.asString ?: "").append('\n')
                "web_search_tool_result" -> {
                    val content = block.get("content")
                    if (content != null && content.isJsonArray) content.asJsonArray.forEach { r ->
                        r.asJsonObject.get("url")?.asString?.let { if (it !in urls) urls.add(it) }
                    }
                }
            }
        }
        val usage = json.getAsJsonObject("usage")
        val searches = usage?.getAsJsonObject("server_tool_use")?.get("web_search_requests")?.asLong ?: 0L
        return Reply(
            text = text.toString().trim(), urls = urls,
            model = json.get("model")?.asString ?: requested,
            tokensIn = usage?.get("input_tokens")?.asLong ?: 0L,
            tokensOut = usage?.get("output_tokens")?.asLong ?: 0L,
            searches = searches,
            stopReason = json.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString
        )
    }

    // ------------------------------------------------------- OpenAI-compatible

    private fun open(url: String, apiKey: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            // OpenRouter asks apps to name themselves; nothing about the user is in it.
            if (url.contains("openrouter.ai")) setRequestProperty("X-Title", "Chronos")
        }

    private fun readBody(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) {
            val why = runCatching {
                val o = JsonParser.parseString(body)
                val e = if (o.isJsonArray) o.asJsonArray[0].asJsonObject.get("error") else o.asJsonObject.get("error")
                if (e != null && e.isJsonObject) e.asJsonObject.get("message")?.asString else e?.asString
            }.getOrNull()
            val reason = why ?: body.trim().take(200).takeIf { it.isNotEmpty() }
            val hint = when (code) {
                401 -> " (the key is refused)"
                403 -> " (the key is refused, or this service is not available on this network: try a VPN)"
                429 -> " (the provider's own limit is reached; try later or another provider)"
                in 500..599 -> " (the provider is busy; try again in a while)"
                else -> ""
            }
            throw IllegalStateException("HTTP $code$hint" + (reason?.let { ": $it" } ?: ""))
        }
        return body
    }

    /** Why a key cannot be the right kind for this provider, or null. Checked before anything is sent. */
    fun keyProblem(p: Profile, apiKey: String): String? =
        if (p.baseUrl.contains("openrouter.ai") && !apiKey.trim().startsWith("sk-or-"))
            "The key saved for ${p.label} is not an OpenRouter key (those start with \"sk-or-\"). " +
                "Paste the key from openrouter.ai/keys in the LLM panel (${p.label} → Edit)."
        else null

    private fun sendCompat(p: Profile, apiKey: String, req: LlmRequest): Reply {
        require(p.model.isNotBlank()) { "No model is chosen for ${p.label}." }
        keyProblem(p, apiKey)?.let { throw IllegalStateException(it) }
        val body = JsonObject().apply {
            addProperty("model", p.model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", req.system()) })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", req.user()) })
            })
            // OpenAI's own newer models take the second name; the rest take the first.
            addProperty(if (p.baseUrl.contains("api.openai.com")) "max_completion_tokens" else "max_tokens", COMPAT_MAX_TOKENS)
        }
        val c = open(p.baseUrl.trimEnd('/') + "/chat/completions", apiKey, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return parseCompat(JsonParser.parseString(readBody(c)).asJsonObject, p.model)
        } finally {
            c.disconnect()
        }
    }

    internal fun parseCompat(json: JsonObject, requested: String): Reply {
        val choice = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
        val content = choice?.getAsJsonObject("message")?.get("content")
        val text = when {
            content == null || content.isJsonNull -> ""
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.joinToString("\n") {
                it.asJsonObject.get("text")?.asString ?: ""
            }
            else -> ""
        }
        val usage = json.getAsJsonObject("usage")
        val finish = choice?.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString
        return Reply(
            text = text.trim(), urls = emptyList(),
            model = json.get("model")?.asString ?: requested,
            tokensIn = usage?.get("prompt_tokens")?.asLong ?: 0L,
            tokensOut = usage?.get("completion_tokens")?.asLong ?: 0L,
            searches = 0,
            stopReason = if (finish == "content_filter") "refusal" else finish
        )
    }

    /** The models a key can use, for the picker. Claude's come from the price list. */
    fun models(p: Profile, apiKey: String?): List<String> {
        if (p.kind == ProviderKind.ANTHROPIC) return PRICES.keys.toList()
        requireNotNull(apiKey) { "Enter the API key first: the list depends on it." }
        val c = open(p.baseUrl.trimEnd('/') + "/models", apiKey, "GET")
        try {
            val json = JsonParser.parseString(readBody(c)).asJsonObject
            val ids = json.getAsJsonArray("data")?.mapNotNull { it.asJsonObject.get("id")?.asString } ?: emptyList()
            // Gemini names them "models/…"; the chat endpoint wants the bare name.
            return ids.map { it.removePrefix("models/") }.distinct()
                .sortedWith(compareBy({ !it.endsWith(":free") }, { it }))
        } finally {
            c.disconnect()
        }
    }
}

/**
 * API keys, one per provider, encrypted with a key that never leaves the
 * Android Keystore. What is stored in preferences is useless off this phone.
 */
object KeyVault {
    private const val ALIAS = "chronos.llm.apikey"
    private const val PREFS = "chronos.llm"
    /** The single key of the first version, before there were several providers. */
    const val LEGACY = "apiKeyEnc"

    private fun field(profileId: String) = "apiKeyEnc.$profileId"

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun save(context: Context, profileId: String, apiKey: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (apiKey.isNullOrBlank()) { prefs.edit().remove(field(profileId)).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val sealed = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(field(profileId), Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context, profileId: String): String? = open(context, field(profileId))

    internal fun open(context: Context, name: String): String? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun has(context: Context, profileId: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(field(profileId))

    /** Moves the first version's single key to the provider it belonged to. */
    fun migrateLegacy(context: Context, profileId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(LEGACY, null) ?: return
        prefs.edit().putString(field(profileId), stored).remove(LEGACY).apply()
    }
}

/** The LLM settings the user chose. Shown and editable in the Navigator tab. */
class LlmSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("chronos.llm", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Every provider set up, in the order they were added. */
    var profiles: List<Profile>
        get() {
            val json = prefs.getString("profiles", null)
            if (json != null) return gson.fromJson(json, object : TypeToken<List<Profile>>() {}.type)
            // First run after there were several providers: the one Claude setting becomes a profile.
            val old = prefs.getString("model", null)
            if (old == null && !prefs.contains(KeyVault.LEGACY)) return emptyList()
            val model = old ?: "claude-opus-5-5"
            val (pin, pout) = LlmClient.PRICES[model] ?: (4.0 to 20.0)
            val p = Profile("anthropic", "anthropic", "Anthropic Claude", ProviderKind.ANTHROPIC,
                "https://api.anthropic.com", model, pin, pout)
            KeyVault.migrateLegacy(context, p.id)
            profiles = listOf(p)
            activeId = p.id
            return listOf(p)
        }
        set(v) = prefs.edit().putString("profiles", gson.toJson(v)).apply()

    var activeId: String?
        get() = prefs.getString("active", null)
        set(v) = prefs.edit().putString("active", v).apply()

    val active: Profile? get() = profiles.let { all -> all.firstOrNull { it.id == activeId } ?: all.firstOrNull() }

    fun put(p: Profile) {
        val all = profiles.filter { it.id != p.id } + p
        profiles = all
        if (activeId == null || all.none { it.id == activeId }) activeId = p.id
    }

    fun remove(id: String) {
        profiles = profiles.filter { it.id != id }
        if (activeId == id) activeId = profiles.firstOrNull()?.id
        KeyVault.save(context, id, null)
    }

    /** Your answer on 3 Oct 2026: two dollars a month. */
    var monthlyCapUsd: Double
        get() = prefs.getFloat("monthlyCapUsd", 2.0f).toDouble()
        set(v) = prefs.edit().putFloat("monthlyCapUsd", v.toFloat()).apply()

    /**
     * Whether triggers may wake the LLM by themselves (stage 5). On by default: the budget,
     * the daily ceiling and the cooldowns still hold, and every answer waits for you.
     */
    var autoTriggers: Boolean
        get() = prefs.getBoolean("autoTriggers", true)
        set(v) = prefs.edit().putBoolean("autoTriggers", v).apply()

    /** The spec's example ceiling on calls per day, across all triggers. */
    var dailyCalls: Int
        get() = prefs.getInt("dailyCalls", 5)
        set(v) = prefs.edit().putInt("dailyCalls", v).apply()
}
