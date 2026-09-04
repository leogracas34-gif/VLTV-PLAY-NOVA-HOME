package com.vltv.play

import android.content.Context
import java.security.MessageDigest

object ParentalControlManager {

    private const val PREFS = "vltv_prefs"
    private const val KEY_ENABLED = "parental_enabled"
    private const val KEY_BLOCKED_CATEGORIES = "blocked_categories"

    // ✅ PIN agora é guardado como hash — nunca em texto puro
    private const val KEY_PIN_HASH = "parental_pin_hash"

    // ✅ Pergunta/resposta secreta usada para redefinir o PIN sem precisar
    // saber o PIN atual. A resposta também é guardada como hash.
    private const val KEY_SECRET_QUESTION = "parental_secret_question"
    private const val KEY_SECRET_ANSWER_HASH = "parental_secret_answer_hash"

    // ✅ Lista central de palavras-chave de conteúdo adulto.
    // Usada por TODAS as telas (Live, VOD, Séries) — um único lugar pra manter,
    // em vez de ter a mesma lista duplicada e podendo ficar dessincronizada
    // em cada Activity.
    private val ADULT_KEYWORDS = listOf(
        "+18", "18+", "adult", "adulto", "xxx", "hot", "sexo", "porn", "erotic", "erótic"
    )

    // ✅ Função central de detecção — qualquer tela que precise saber se um
    // nome de canal/categoria/filme/série é adulto, chama esta função.
    fun isAdultName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return ADULT_KEYWORDS.any { n.contains(it) }
    }

    private fun sha256(texto: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ✅ Indica se o usuário já trocou o PIN do padrão "0000" pra um próprio.
    // Usado pra decidir se a tela mostra o formulário de criar PIN ou os
    // botões de Alterar/Esqueci.
    fun hasCustomPin(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.contains(KEY_PIN_HASH)
    }

    fun setPin(context: Context, pin: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PIN_HASH, sha256(pin)).apply()
    }

    // ✅ Substitui o antigo getPin() — agora não dá mais pra "ler" o PIN
    // salvo, só verificar se um valor digitado bate com o hash guardado.
    fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hashSalvo = prefs.getString(KEY_PIN_HASH, null)
        return if (hashSalvo != null) {
            hashSalvo == sha256(pin)
        } else {
            // Nenhum PIN customizado ainda → valor padrão "0000"
            pin == "0000"
        }
    }

    fun hasSecretQuestion(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.contains(KEY_SECRET_ANSWER_HASH)
    }

    fun setSecretQuestion(context: Context, question: String, answer: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SECRET_QUESTION, question)
            .putString(KEY_SECRET_ANSWER_HASH, sha256(answer.trim().lowercase()))
            .apply()
    }

    fun getSecretQuestion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SECRET_QUESTION, null)
    }

    fun verifySecretAnswer(context: Context, answer: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hashSalvo = prefs.getString(KEY_SECRET_ANSWER_HASH, null) ?: return false
        return hashSalvo == sha256(answer.trim().lowercase())
    }

    fun getBlockedCategories(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_BLOCKED_CATEGORIES, emptySet()) ?: emptySet()
    }

    fun toggleCategoryBlocked(context: Context, categoryId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_BLOCKED_CATEGORIES, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        if (current.contains(categoryId)) current.remove(categoryId) else current.add(categoryId)
        prefs.edit().putStringSet(KEY_BLOCKED_CATEGORIES, current).apply()
    }

    fun isCategoryBlocked(context: Context, categoryId: String): Boolean {
        return getBlockedCategories(context).contains(categoryId)
    }
}
