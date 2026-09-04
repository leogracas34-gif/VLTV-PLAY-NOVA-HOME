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
 * FIREBASE CONSOLE:
 *   Chave: show_copa_icon | Tipo: Boolean
 *   true  → ícone Copa (verde com losango amarelo)
 *   false → ícone normal
 *
 * ✅ CORREÇÃO: verifica o estado atual ANTES de trocar.
 *    Se o ícone já está correto, não faz nada → app NÃO fecha/reinicia.
 *    O app só reinicia quando há troca real de alias.
 */
object IconeSazonalHelper {

    private const val KEY_COPA     = "show_copa_icon"
    private const val ALIAS_NORMAL = "com.vltv.play.LauncherNormal"
    private const val ALIAS_COPA   = "com.vltv.play.LauncherCopa"

    /**
     * Chamado pela HomeActivity APÓS fetchAndActivate completar.
     * Lê o valor já ativado do Remote Config e troca o ícone se necessário.
     */
    fun aplicar(context: Context) {
        val mostrarCopa = Firebase.remoteConfig.getBoolean(KEY_COPA)
        trocarIcone(context, mostrarCopa)
    }

    private fun trocarIcone(context: Context, usarCopa: Boolean) {
        val pm = context.packageManager
        val aliasAtivo   = if (usarCopa) ALIAS_COPA   else ALIAS_NORMAL
        val aliasInativo = if (usarCopa) ALIAS_NORMAL else ALIAS_COPA

        // ✅ CHAVE DO PROBLEMA: só chama setComponentEnabledSetting se o estado
        //    atual for diferente do desejado. Sem essa verificação, o Android
        //    reinicia o app mesmo com DONT_KILL_APP toda vez que o método é chamado.
        val estadoAtual = pm.getComponentEnabledSetting(
            ComponentName(context, aliasAtivo)
        )
        if (estadoAtual == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return // Ícone já está correto — não faz nada, app não reinicia
        }

        try {
            pm.setComponentEnabledSetting(
                ComponentName(context, aliasAtivo),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(context, aliasInativo),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
