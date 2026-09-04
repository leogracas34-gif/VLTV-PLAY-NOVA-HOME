package com.vltv.play

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vltv.play.databinding.ActivitySplashBinding
import java.io.PrintWriter
import java.io.StringWriter

// ✅ NOVO: Splash própria e animada, substituindo a splash "crua" (ícone
// parado no centro) que vinha só do tema Theme.VLTVPlay.Splash.
//
// Agora ELA é o launcher de fato (ver AndroidManifest.xml — os
// activity-aliases LauncherNormal/LauncherCopa passaram a apontar pra cá em
// vez de para a LoginActivity). O tema Theme.VLTVPlay.Splash continua
// existindo e aplicado nesta Activity só para cobrir o instante de
// carregamento do processo (splash nativa do sistema, bem curta) — assim
// que esta tela desenha, ela assume e a animação de verdade começa.
//
// Fluxo: letras "V L T V" entram uma a uma (fade + sobe de baixo pra cima
// com leve "overshoot") → triângulo de play aparece com um pequeno pop →
// "PLAY" surge com o letter-spacing se abrindo → um brilho (shimmer) passa
// uma vez por cima do wordmark → tela inteira dá fade-out e abre a
// LoginActivity.
//
// ✅ CORREÇÃO (aparelhos Samsung/Motorola de entrada): em aparelhos com a
// escala de animação do sistema reduzida a 0 — o que acontece
// automaticamente quando o usuário liga "Remover animações"
// (Acessibilidade, comum em Samsung) ou modos de economia de
// bateria/performance (comum em Motorola) — o ViewPropertyAnimator pode não
// aplicar o valor final da animação corretamente, sobretudo combinado com
// OvershootInterpolator (que ultrapassa 1.0 e volta). Resultado: a view
// fica travada no estado inicial invisível (alpha = 0) e o app já segue pro
// Login, dando a impressão de que "o efeito não aparece". A correção tem
// duas camadas: (1) cada animate() agora força o estado final via
// withEndAction, e (2) garantirEstadoFinal() roda como rede de segurança
// logo antes de ir pro Login, cobrindo qualquer animação que tenha sido
// pulada por outro motivo.
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())

    // Tempo total (ms) que a splash fica na tela antes de ir pro Login.
    // Ajuste este número se quiser a animação mais rápida ou mais lenta.
    private val DURACAO_TOTAL_MS = 2400L

    override fun onCreate(savedInstanceState: Bundle?) {
        // ✅ NOVO (temporário, pra debug): captura QUALQUER crash não
        // tratado no app inteiro — não só aqui na splash — e abre a
        // CrashActivity mostrando o erro completo em vez do app simplesmente
        // fechar sem explicação. Instalado logo no início porque a
        // SplashActivity é sempre a primeira tela a abrir.
        instalarCapturadorDeCrash()

        // ✅ CORREÇÃO DO CRASH: essa chamada precisa vir ANTES do
        // super.onCreate(). É ela quem consome o tema Theme.VLTVPlay.Splash
        // (que não é AppCompat, é Theme.SplashScreen) e troca
        // automaticamente para o postSplashScreenTheme (Theme.VLTVPlay, que
        // aí sim é AppCompat) antes de qualquer setContentView() rodar. Sem
        // essa linha, o AppCompatActivity tenta montar a tela ainda com o
        // tema de splash "cru" e quebra com IllegalStateException.
        installSplashScreen()

        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        aplicarModoImersivo()
        prepararEstadoInicial()
        animarEntrada()

        handler.postDelayed({
            // ✅ Rede de segurança: garante que tudo esteja no estado
            // visível final antes de sair da tela, mesmo que alguma
            // animação individual não tenha completado corretamente.
            garantirEstadoFinal()
            irParaLogin()
        }, DURACAO_TOTAL_MS)
    }

    private fun instalarCapturadorDeCrash() {
        val handlerPadrao = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val trace = sw.toString()
                Log.e("VLTV_CRASH", trace)

                val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                    putExtra("stacktrace", trace)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                applicationContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e("VLTV_CRASH", "Falha ao mostrar CrashActivity", e)
            }

            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    private fun aplicarModoImersivo() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
    }

    // Garante que tudo comece invisível/deslocado antes de animar — evita
    // "pulo" visual caso a Activity seja recriada (ex: rotação de tela).
    private fun prepararEstadoInicial() {
        val letras = listOf(binding.letterV1, binding.letterL, binding.letterT, binding.letterV2)
        letras.forEach {
            it.alpha = 0f
            it.translationY = 40f
        }
        binding.ivPlayTriangle.alpha = 0f
        binding.ivPlayTriangle.scaleX = 0.3f
        binding.ivPlayTriangle.scaleY = 0.3f
        binding.tvPlay.alpha = 0f
        binding.tvPlay.letterSpacing = 0f
        binding.viewShimmer.alpha = 0f
        binding.viewShimmer.translationX = -400f
    }

    private fun animarEntrada() {
        val letras = listOf(binding.letterV1, binding.letterL, binding.letterT, binding.letterV2)
        var delay = 80L

        // Letras do "VLTV" entrando uma a uma
        letras.forEach { letra ->
            letra.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(420)
                .setInterpolator(OvershootInterpolator(1.6f))
                .withEndAction {
                    // ✅ Força o estado final — cobre o caso de a escala de
                    // animação do sistema estar em 0 (Samsung "Remover
                    // animações", economia de bateria Motorola etc.)
                    letra.alpha = 1f
                    letra.translationY = 0f
                }
                .start()
            delay += 90L
        }

        // Triângulo de play entra logo depois da última letra
        binding.ivPlayTriangle.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay + 80L)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                binding.ivPlayTriangle.alpha = 1f
                binding.ivPlayTriangle.scaleX = 1f
                binding.ivPlayTriangle.scaleY = 1f
            }
            .start()

        // "PLAY" surge com o espaçamento entre letras se abrindo
        val delayPlay = delay + 260L
        binding.tvPlay.animate()
            .alpha(1f)
            .setStartDelay(delayPlay)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.tvPlay.alpha = 1f
            }
            .start()
        animarLetterSpacing(binding.tvPlay, 0f, 0.25f, 500, delayPlay)

        // Brilho passando uma única vez por cima do "VLTV"
        val delayShimmer = delayPlay + 350L
        binding.viewShimmer.animate()
            .alpha(1f)
            .setStartDelay(delayShimmer)
            .setDuration(120)
            .start()
        binding.viewShimmer.animate()
            .translationX(400f)
            .setStartDelay(delayShimmer)
            .setDuration(650)
            .withEndAction { binding.viewShimmer.alpha = 0f }
            .start()
    }

    private fun animarLetterSpacing(view: TextView, de: Float, ate: Float, duracao: Long, delay: Long) {
        val animator = ValueAnimator.ofFloat(de, ate)
        animator.startDelay = delay
        animator.duration = duracao
        animator.addUpdateListener { view.letterSpacing = it.animatedValue as Float }
        // ✅ Garante o valor final de letterSpacing mesmo se a duração
        // efetiva da animação for 0 (escala de animação do sistema
        // desligada) e o addUpdateListener nunca rodar valores intermediários.
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.letterSpacing = ate
            }
        })
        animator.start()
    }

    // ✅ Rede de segurança: força TODAS as views ao estado visual final,
    // independente de qualquer animação individual ter completado. Chamada
    // logo antes do fade-out para o Login.
    private fun garantirEstadoFinal() {
        val letras = listOf(binding.letterV1, binding.letterL, binding.letterT, binding.letterV2)
        letras.forEach {
            it.alpha = 1f
            it.translationY = 0f
        }
        binding.ivPlayTriangle.alpha = 1f
        binding.ivPlayTriangle.scaleX = 1f
        binding.ivPlayTriangle.scaleY = 1f
        binding.tvPlay.alpha = 1f
        binding.tvPlay.letterSpacing = 0.25f
        binding.viewShimmer.alpha = 0f
    }

    private fun irParaLogin() {
        binding.root.animate()
            .alpha(0f)
            .setDuration(280)
            .withEndAction {
                val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
                overridePendingTransition(0, 0)
                finish()
            }
            .start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
