package com.vltv.play

import android.content.Context
import java.security.MessageDigest

/**
 * ✅ PIN independente do Controle Parental — protege exclusivamente a TROCA
 * DE PERFIL quando o usuário está saindo do perfil Infantil para qualquer
 * outro perfil (adulto).
 *
 * Por que separado do ParentalControlManager?
 *   - O Controle Parental protege CONTEÚDO (bloqueia categorias adultas,
 *     violência etc). Ele pode estar DESATIVADO (ex: usuário adulto
 *     assistindo algo +18) sem que isso tenha relação nenhuma com a
 *     segurança do perfil Infantil.
 *   - Esse aqui protege a SESSÃO/PERFIL — impede que a criança, sozinha,
 *     saia do modo Kids e entre num perfil adulto. É uma preocupação
 *     completamente diferente e precisa funcionar mesmo que o usuário
 *     nunca tenha configurado (ou tenha desativado) o Controle Parental.
 *   - São PINs guardados em arquivos de SharedPreferences DIFERENTES —
 *     ativar/mudar um não afeta o outro. Não é obrigatório usar o mesmo
 *     PIN nos dois.
 *
 * Comportamento (igual ao ParentalControlManager, por consistência):
 *   - `isEnabled` começa `false` — a proteção é opt-in.
 *   - Ao chamar `setEnabled(true)` pela primeira vez, já fica protegido
 *     com o PIN padrão "0000" até o usuário customizar (`hasCustomPin`
 *     continua `false` até lá — a tela de Configurações deve usar isso
 *     pra sugerir "Criar PIN agora").
 *   - Suporta pergunta secreta pra recuperação ("Esqueci o PIN").
 *   - PIN e resposta secreta são guardados como hash SHA-256, nunca em
 *     texto puro.
 */
object ProfileSwitchPinManager {

    private const val PREFS_NAME = "vltv_profile_switch_pin"

    private const val KEY_ENABLED            = "enabled"
    private const val KEY_PIN_HASH           = "pin_hash"
    private const val KEY_HAS_CUSTOM_PIN     = "has_custom_pin"
    private const val KEY_SECRET_QUESTION    = "secret_question"
    private const val KEY_SECRET_ANSWER_HASH = "secret_answer_hash"

    private const val DEFAULT_PIN = "0000"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hash(texto: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(texto.trim().lowercase().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Ativar / desativar ──────────────────────────────────────────────
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        // Ao ativar pela primeira vez, garante que já exista um PIN válido
        // (o padrão "0000") mesmo antes do usuário customizar — assim a
        // proteção já funciona na hora, só que com um PIN fraco/óbvio até
        // ele trocar.
        if (enabled && prefs(context).getString(KEY_PIN_HASH, null) == null) {
            prefs(context).edit()
                .putString(KEY_PIN_HASH, hash(DEFAULT_PIN))
                .putBoolean(KEY_HAS_CUSTOM_PIN, false)
                .apply()
        }
    }

    // ── PIN ──────────────────────────────────────────────────────────────
    fun hasCustomPin(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAS_CUSTOM_PIN, false)

    fun setPin(context: Context, pin: String) {
        prefs(context).edit()
            .putString(KEY_PIN_HASH, hash(pin))
            .putBoolean(KEY_HAS_CUSTOM_PIN, true)
            .apply()
    }

    fun verifyPin(context: Context, pinDigitado: String): Boolean {
        val salvo = prefs(context).getString(KEY_PIN_HASH, null) ?: hash(DEFAULT_PIN)
        return salvo == hash(pinDigitado)
    }

    // ── Pergunta secreta (recuperação) ─────────────────────────────────
    fun hasSecretQuestion(context: Context): Boolean =
        !prefs(context).getString(KEY_SECRET_QUESTION, null).isNullOrBlank()

    fun getSecretQuestion(context: Context): String? =
        prefs(context).getString(KEY_SECRET_QUESTION, null)

    fun setSecretQuestion(context: Context, pergunta: String, resposta: String) {
        prefs(context).edit()
            .putString(KEY_SECRET_QUESTION, pergunta)
            .putString(KEY_SECRET_ANSWER_HASH, hash(resposta))
            .apply()
    }

    fun verifySecretAnswer(context: Context, resposta: String): Boolean {
        val salvo = prefs(context).getString(KEY_SECRET_ANSWER_HASH, null) ?: return false
        return salvo == hash(resposta)
    }

    /**
     * Usado pelo fluxo "Esqueci o PIN" em Configurações: depois de validar
     * a resposta secreta, permite definir um PIN novo sem precisar saber
     * o antigo.
     */
    fun resetParaNovoPin(context: Context, novoPin: String) {
        setPin(context, novoPin)
    }
}
