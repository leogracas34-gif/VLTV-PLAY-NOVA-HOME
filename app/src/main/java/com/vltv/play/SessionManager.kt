package com.vltv.play

/**
 * ✅ Controla se já existe uma "sessão de perfil" ativa dentro deste
 * processo do app — igual ao comportamento da Netflix.
 *
 * Por ser um `object` (singleton) com uma variável simples em memória
 * (sem SharedPreferences, sem banco), o valor:
 *   - Permanece `true` enquanto o processo do app estiver vivo, mesmo
 *     que o usuário apenas minimize o app (botão Home, troca de app).
 *   - Volta automaticamente para `false` sempre que o processo morre de
 *     verdade (app fechado pelo usuário, sistema mata por falta de
 *     memória, ou celular reiniciado) — porque nesse caso a JVM/Dalvik
 *     é recriada do zero e este objeto é reconstruído com seu valor
 *     inicial (false).
 *
 * Uso:
 *   - Quando o usuário seleciona/entra em um perfil (ProfilesActivity
 *     ou troca de perfil na SettingsActivity), chame:
 *         SessionManager.marcarSessaoAtiva()
 *
 *   - No LoginActivity.decidirProximaTela(), consulte:
 *         SessionManager.sessaoAtiva
 *     para decidir se pode pular direto para a HomeActivity ou se deve
 *     forçar a passagem pela tela de seleção de perfil.
 */
object SessionManager {

    var sessaoAtiva: Boolean = false
        private set

    fun marcarSessaoAtiva() {
        sessaoAtiva = true
    }

    // ✅ Útil para o fluxo de logout — força a tela de perfil a aparecer
    // de novo mesmo que o processo continue vivo depois do logout.
    fun encerrarSessao() {
        sessaoAtiva = false
    }
}
