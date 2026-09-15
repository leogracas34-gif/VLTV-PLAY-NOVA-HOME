package com.vltv.play

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig

/**
 * IconeSazonalHelper
 *
 * Controla qual ícone de launcher está ativo via Firebase Remote Config.
 * O fetchAndActivate é feito na HomeActivity — aqui apenas lemos o valor já ativado.
 *
 * ✅ ATUALIZADO: agora suporta MÚLTIPLOS temas de ícone (Halloween, Dia das
 * Crianças, Natal, Ano Novo), não só a Copa. A troca continua 100% remota,
 * sem gerar APK novo — desde que os ícones de cada tema já estejam
 * compilados no APK atual (ver instruções no AndroidManifest.xml).
 *
 * FIREBASE CONSOLE:
 *   Chave: tema_icone_ativo | Tipo: String
 *   Valores aceitos: "normal" | "copa" | "halloween" | "criancas" | "natal" | "ano_novo" | "carnaval"
 *   Qualquer outro valor (ou vazio) cai no ícone "normal".
 *
 *   Compatibilidade: se "tema_icone_ativo" estiver vazia, o helper ainda
 *   olha a chave antiga "show_copa_icon" (Boolean) pra não quebrar quem
 *   já tinha essa config publicada. Assim que você começar a usar
 *   "tema_icone_ativo" no console, pode apagar a "show_copa_icon".
 *
 * Pra trocar o ícone: mude o valor de "tema_icone_ativo" no Remote Config
 * e publique. O app aplica em até 1h (ou na próxima abertura, por causa do
 * minimumFetchIntervalInSeconds configurado na HomeActivity).
 */
object IconeSazonalHelper {

    private const val KEY_TEMA        = "tema_icone_ativo"
    private const val KEY_COPA_LEGADO = "show_copa_icon"

    // Nome completo dos activity-aliases declarados no AndroidManifest.xml
    private const val PACOTE = "com.vltv.play"
    private val ALIASES = mapOf(
        "normal"    to "$PACOTE.LauncherNormal",
        "copa"      to "$PACOTE.LauncherCopa",
        "halloween" to "$PACOTE.LauncherHalloween",
        "criancas"  to "$PACOTE.LauncherCriancas",
        "natal"     to "$PACOTE.LauncherNatal",
        "ano_novo"  to "$PACOTE.LauncherAnoNovo",
        "carnaval"  to "$PACOTE.LauncherCarnaval"
    )

    /**
     * Chamado pela HomeActivity APÓS fetchAndActivate completar.
     * Lê o valor já ativado do Remote Config e troca o ícone se necessário.
     */
    fun aplicar(context: Context) {
        val remoteConfig = Firebase.remoteConfig
        var temaDesejado = remoteConfig.getString(KEY_TEMA).trim().lowercase()

        // Compatibilidade com a config antiga (booleana, só Copa)
        if (temaDesejado.isEmpty()) {
            temaDesejado = if (remoteConfig.getBoolean(KEY_COPA_LEGADO)) "copa" else "normal"
        }

        // Valor desconhecido/inválido → cai no ícone normal, nunca quebra o app
        val aliasDesejado = ALIASES[temaDesejado] ?: ALIASES.getValue("normal")

        trocarIcone(context, aliasDesejado)
    }

    private fun trocarIcone(context: Context, aliasDesejado: String) {
        val pm = context.packageManager

        // ✅ CHAVE DO PROBLEMA (mantida da versão anterior): só mexe nos
        //    componentes se o estado atual for diferente do desejado.
        //    Sem essa verificação, o Android reinicia o app mesmo com
        //    DONT_KILL_APP toda vez que o método é chamado.
        val estadoAtual = pm.getComponentEnabledSetting(ComponentName(context, aliasDesejado))
        if (estadoAtual == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return // Ícone já está correto — não faz nada, app não reinicia
        }

        try {
            // Ativa o alias desejado
            pm.setComponentEnabledSetting(
                ComponentName(context, aliasDesejado),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // Desativa todos os outros aliases de tema
            ALIASES.values
                .filter { it != aliasDesejado }
                .forEach { alias ->
                    pm.setComponentEnabledSetting(
                        ComponentName(context, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
