package com.vltv.play

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ✅ Favoritos são separados POR PERFIL — mesmo padrão já usado em
// downloads (DownloadEntity.profile_name) e histórico de reprodução
// (WatchHistoryEntity.profile_name / "${currentProfile}_local_history_ids"
// no PlayerActivity). Assim o perfil Infantil nunca vê os favoritos do
// perfil adulto e vice-versa, consistente com o resto do app.
object FavoritesManager {

    private const val PREFS = "vltv_prefs"
    private val gson = Gson()

    private fun perfilAtual(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("last_profile_name", null)?.takeIf { it.isNotBlank() } ?: "Padrao"
    }

    private fun chave(context: Context) = "${perfilAtual(context)}_favorite_channels_json"

    fun getFavorites(context: Context): List<LiveStream> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(chave(context), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LiveStream>>() {}.type
            gson.fromJson<List<LiveStream>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isFavorite(context: Context, channelId: Int): Boolean =
        getFavorites(context).any { it.id == channelId }

    // Retorna o novo estado (true = acabou de favoritar, false = acabou
    // de desfavoritar), pra quem chamou já saber o que aconteceu sem
    // precisar consultar de novo.
    fun toggleFavorite(context: Context, channel: LiveStream): Boolean {
        val atuais = getFavorites(context).toMutableList()
        val idx = atuais.indexOfFirst { it.id == channel.id }
        val agoraFavoritado: Boolean
        if (idx != -1) {
            atuais.removeAt(idx)
            agoraFavoritado = false
        } else {
            atuais.add(channel)
            agoraFavoritado = true
        }
        salvar(context, atuais)
        return agoraFavoritado
    }

    private fun salvar(context: Context, lista: List<LiveStream>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(chave(context), gson.toJson(lista)).apply()
    }
}
