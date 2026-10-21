package com.zhypix.fresh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * User-configured AI providers. Base URL, API key and model names all come
 * from the user - the app ships with zero hardcoded providers or models.
 * Keys live in EncryptedSharedPreferences (AES-256 via Android Keystore) and
 * are never written to logs.
 */
class ProviderStore(context: Context) {

    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val models: List<String>
    )

    data class Selection(val providerId: String, val model: String)

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "providers_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore unavailable (rare): fall back to app-private storage rather than crashing.
        context.getSharedPreferences("providers_fallback", Context.MODE_PRIVATE)
    }

    private val moshi = Moshi.Builder().build()
    private val listAdapter = moshi.adapter<List<Map<String, Any?>>>(
        Types.newParameterizedType(List::class.java, Map::class.java, String::class.java, Any::class.java)
    )

    @Synchronized
    fun providers(): List<Provider> {
        val raw = prefs.getString(KEY_PROVIDERS, null) ?: return emptyList()
        val list = try { listAdapter.fromJson(raw) } catch (e: Exception) { null } ?: return emptyList()
        return list.mapNotNull { m ->
            val id = m["id"] as? String ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val baseUrl = m["baseUrl"] as? String ?: return@mapNotNull null
            val apiKey = m["apiKey"] as? String ?: return@mapNotNull null
            val models = (m["models"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            Provider(id, name, baseUrl, apiKey, models)
        }
    }

    @Synchronized
    fun saveProvider(provider: Provider) {
        val all = providers().filterNot { it.id == provider.id } + provider
        persist(all)
    }

    @Synchronized
    fun deleteProvider(id: String) {
        persist(providers().filterNot { it.id == id })
        if (selection()?.providerId == id) clearSelection()
    }

    private fun persist(all: List<Provider>) {
        val maps = all.map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "baseUrl" to it.baseUrl,
                "apiKey" to it.apiKey,
                "models" to it.models
            )
        }
        prefs.edit().putString(KEY_PROVIDERS, listAdapter.toJson(maps)).apply()
    }

    fun selection(): Selection? {
        val pid = prefs.getString(KEY_SEL_PROVIDER, null) ?: return null
        val model = prefs.getString(KEY_SEL_MODEL, null) ?: return null
        return Selection(pid, model)
    }

    fun saveSelection(sel: Selection) {
        prefs.edit()
            .putString(KEY_SEL_PROVIDER, sel.providerId)
            .putString(KEY_SEL_MODEL, sel.model)
            .apply()
    }

    fun clearSelection() {
        prefs.edit().remove(KEY_SEL_PROVIDER).remove(KEY_SEL_MODEL).apply()
    }

    fun selectedProvider(): Pair<Provider, String>? {
        val sel = selection() ?: return null
        val provider = providers().firstOrNull { it.id == sel.providerId } ?: return null
        val model = if (provider.models.contains(sel.model)) sel.model else provider.models.firstOrNull() ?: return null
        return provider to model
    }

    companion object {
        private const val KEY_PROVIDERS = "providers_json"
        private const val KEY_SEL_PROVIDER = "selected_provider_id"
        private const val KEY_SEL_MODEL = "selected_model"
    }
}
