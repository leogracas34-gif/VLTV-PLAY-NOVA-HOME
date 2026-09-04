package com.vltv.play.retro

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Classifica o aparelho em duas categorias, pra decidir quais jogos
 * retrô (do games.json) aparecem na lista.
 */
enum class RetroDeviceTier {
    BASICO,
    AVANCADO
}

object DeviceTierHelper {

    // RAM mínima (em MB) pra considerar o aparelho apto a rodar cores
    // mais pesados (ex: N64) com folga. Abaixo disso, o aparelho fica
    // no tier básico (NES/SNES/Genesis/GBA), que já roda bem em
    // qualquer celular.
    private const val RAM_MINIMA_AVANCADO_MB = 3072 // 3GB

    // Versão mínima do Android pro tier avançado. Aparelhos muito
    // antigos costumam ter WebView desatualizado, o que compromete o
    // desempenho dos cores em WebAssembly mesmo com RAM suficiente.
    private const val SDK_MINIMO_AVANCADO = Build.VERSION_CODES.Q // Android 10

    fun detectarTier(context: Context): RetroDeviceTier {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return RetroDeviceTier.BASICO

            if (activityManager.isLowRamDevice) {
                return RetroDeviceTier.BASICO
            }

            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRamMb = memoryInfo.totalMem / (1024 * 1024)

            val ramSuficiente = totalRamMb >= RAM_MINIMA_AVANCADO_MB
            val androidSuficiente = Build.VERSION.SDK_INT >= SDK_MINIMO_AVANCADO

            if (ramSuficiente && androidSuficiente) RetroDeviceTier.AVANCADO else RetroDeviceTier.BASICO
        } catch (e: Exception) {
            RetroDeviceTier.BASICO
        }
    }
}
