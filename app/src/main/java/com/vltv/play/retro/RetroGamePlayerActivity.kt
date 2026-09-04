package com.vltv.play.retro

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vltv.play.R

/**
 * Tela full-screen que carrega a página do EmulatorJS hospedada na VPS
 * (https://cdn.vltvplay.tech/retro/index.html) passando a ROM escolhida.
 */
class RetroGamePlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROM_URL = "extra_rom_url"
        const val EXTRA_CORE = "extra_core"
        const val EXTRA_TITLE = "extra_title"

        // ✅ NOVO: URL do BIOS (só preenchido quando o jogo é PS1). O
        // index.html do EmulatorJS já sabe ler esse parâmetro e usar o
        // BIOS certo; pra qualquer outro console essa extra simplesmente
        // não é enviada e o comportamento continua o de sempre.
        const val EXTRA_BIOS = "extra_bios"

        // Atenção: o /retro/ está dentro do server block de cdn.vltvplay.tech na VPS
        private const val EMULATOR_PAGE = "https://cdn.vltvplay.tech/retro/index.html"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ativarModoImersivo()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_retro_game_player)
        webView = findViewById(R.id.webViewRetroPlayer)

        val romUrl = intent.getStringExtra(EXTRA_ROM_URL) ?: return
        val core = intent.getStringExtra(EXTRA_CORE) ?: "nes"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "VLTV+ Jogos Retrô"
        val bios = intent.getStringExtra(EXTRA_BIOS)

        setupWebView()

        val uriBuilder = Uri.parse(EMULATOR_PAGE).buildUpon()
            .appendQueryParameter("rom", romUrl)
            .appendQueryParameter("core", core)
            .appendQueryParameter("title", title)

        // ✅ Só anexa &bios=... quando o valor existir e não for vazio —
        // pra NES/SNES/N64/etc a extra vem null e essa linha nem entra na URL.
        if (!bios.isNullOrBlank()) {
            uriBuilder.appendQueryParameter("bios", bios)
        }

        webView.loadUrl(uriBuilder.build().toString())
    }

    // ✅ CORRIGIDO: FLAG_FULLSCREEN sozinho só esconde a barra de status
    // (relógio) — a barra de NAVEGAÇÃO do sistema (voltar/home/recentes),
    // que em paisagem fica encostada numa lateral da tela, continuava
    // visível e cobrindo os controles do emulador (D-pad, botões A/B/X/Y).
    // WindowInsetsControllerCompat com hide(systemBars()) esconde as DUAS
    // barras de vez, e BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE permite que
    // elas voltem temporariamente com um swipe da borda, sem precisar sair
    // do modo imersivo.
    private fun ativarModoImersivo() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // ✅ Reforça o modo imersivo sempre que a janela ganha foco de novo
        // (ex: usuário deu swipe pra ver a barra, ou voltou de outro app) —
        // sem isso, a barra pode ficar "grudada" visível depois do 1º swipe.
        if (hasFocus) {
            ativarModoImersivo()
            webView.requestFocus()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // ✅ Sem isso, numa Android TV o WebView pode nunca receber foco, e
        // as teclas do D-pad do controle (setas/OK) não chegam nem como
        // evento de teclado dentro da página do EmulatorJS. Isso garante
        // que o WebView pegue o foco assim que a tela abre e sempre que a
        // Activity volta a ficar em primeiro plano.
        //
        // ⚠️ Isso só garante que a TECLA chegue até a página. O que cada
        // seta do controle FAZ dentro do jogo (mover, pular, atirar) é
        // definido pelo próprio EmulatorJS (index.html hospedado na VPS,
        // cdn.vltvplay.tech/retro/), fora deste arquivo Kotlin.
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
