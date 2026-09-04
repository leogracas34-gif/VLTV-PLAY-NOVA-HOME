package com.vltv.play

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vltv.play.databinding.ActivityCrashBinding

// ✅ NOVO: tela de diagnóstico de crash.
//
// Quando qualquer exceção não tratada acontece em qualquer lugar do app
// (registrado lá na SplashActivity via Thread.setDefaultUncaughtExceptionHandler),
// em vez do app simplesmente fechar sem explicação, ele abre ESTA tela
// mostrando o erro completo (stacktrace) em texto selecionável, com um
// botão para copiar tudo de uma vez.
//
// Isso é só uma ferramenta de debug temporária — depois que resolvermos o
// bug que está fechando o app, dá pra manter (é útil pra sempre) ou
// remover, como você preferir.
class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val trace = intent.getStringExtra("stacktrace") ?: "Erro desconhecido (sem stacktrace)."
        binding.tvStacktrace.text = trace

        binding.btnCopiar.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("crash_log", trace))
            Toast.makeText(this, "Copiado! Cole aqui no chat com o Claude.", Toast.LENGTH_SHORT).show()
        }

        binding.btnCompartilhar.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, trace)
            }
            startActivity(Intent.createChooser(shareIntent, "Enviar erro"))
        }

        binding.btnFechar.setOnClickListener {
            finishAffinity()
        }
    }
}
