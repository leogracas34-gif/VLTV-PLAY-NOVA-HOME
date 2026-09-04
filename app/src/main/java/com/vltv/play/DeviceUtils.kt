package com.vltv.play

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Detecção central de TV / Android TV, usada em TODAS as telas do app.
 *
 * Antes cada Activity (Home, Details, SeriesDetails, Vod, Series, Search,
 * LiveTv, Login) reimplementava essa checagem localmente, e cada uma
 * verificava um subconjunto diferente de sinais — o que fazia algumas
 * telas reconhecerem uma caixa de TV como "celular" (e vice-versa) mesmo
 * sendo o mesmo aparelho físico, quebrando o foco do D-pad só naquela tela.
 *
 * Esta função reúne os 3 sinais que existiam espalhados:
 *  - UiModeManager (Configuration.UI_MODE_TYPE_TELEVISION)
 *  - PackageManager (features leanback / television / live_tv)
 *  - Ausência de touchscreen (comum em Fire TV / caixas Android TV genéricas
 *    que às vezes não reportam corretamente o uiMode ou as features acima)
 *
 * Qualquer tela que precise saber se está rodando em TV (pra decidir foco
 * de D-pad, orientação, esconder bottom nav, etc.) deve usar esta função
 * — via `context.isTelevisionDevice()` — em vez de reimplementar a checagem.
 */
fun Context.isTelevisionDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isTvUiMode =
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    val pm = packageManager
    val hasTvFeature =
        pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)

    val semTouchscreen = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    return isTvUiMode || hasTvFeature || semTouchscreen
}
