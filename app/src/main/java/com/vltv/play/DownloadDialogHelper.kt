package com.vltv.play

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

// ────────────────────────────────────────────────────────────────
// Diálogos premium (fundo escuro, cantos arredondados, sem AlertDialog
// padrão) usados nas telas de download.
//
// ✅ NOVO: confirmarAcaoDupla — diálogo com DUAS ações principais (ex:
// "Pausar" e "Cancelar", ou "Continuar" e "Cancelar") empilhadas
// verticalmente, mais um "Voltar" pequeno embaixo pra fechar sem fazer
// nada. Usado no botão "X" dos itens de download, que agora se comporta
// assim:
//   - Baixando ou na fila  → Pausar Download / Cancelar Download
//   - Pausado              → Continuar Download / Cancelar Download
// ────────────────────────────────────────────────────────────────
object DownloadDialogHelper {

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun confirmarAcao(
        context: Context,
        titulo: String,
        mensagem: String,
        btnPositivo: String,
        corPositivo: String = "#FFFFFF",
        onConfirmar: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(20))
        }

        root.addView(TextView(context).apply {
            text = titulo; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(10) }
        })

        root.addView(TextView(context).apply {
            text = mensagem; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(20) }
        })

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val corBtnPos = try { Color.parseColor(corPositivo) } catch (e: Exception) { Color.WHITE }
        val isDestructive = corPositivo == "#FF5252"

        btnRow.addView(TextView(context).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, context.dp(48), 1f).apply { marginEnd = context.dp(6) }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = context.dp(8).toFloat(); setStroke(context.dp(1), Color.parseColor("#2A2A2A")) }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        })

        btnRow.addView(TextView(context).apply {
            text = btnPositivo; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDestructive) Color.WHITE else Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, context.dp(48), 1f).apply { marginStart = context.dp(6) }
            background = GradientDrawable().apply { setColor(corBtnPos); cornerRadius = context.dp(8).toFloat() }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss(); onConfirmar() }
        })

        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = context.dp(16).toFloat() })
            val p = attributes; p.width = (context.resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
        }
        dialog.show()
    }

    // ✅ NOVO
    fun confirmarAcaoDupla(
        context: Context,
        titulo: String,
        mensagem: String,
        btnPrincipal: String,
        corPrincipal: String = "#FFFFFF",
        onPrincipal: () -> Unit,
        btnSecundario: String,
        corSecundario: String = "#FF5252",
        onSecundario: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(16))
        }

        root.addView(TextView(context).apply {
            text = titulo; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(10) }
        })

        root.addView(TextView(context).apply {
            text = mensagem; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(20) }
        })

        fun criarBotao(texto: String, cor: String, onClick: () -> Unit): TextView {
            val corInt = try { Color.parseColor(cor) } catch (e: Exception) { Color.WHITE }
            val isDestructive = cor == "#FF5252"
            return TextView(context).apply {
                text = texto; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isDestructive) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48))
                    .apply { bottomMargin = context.dp(10) }
                background = GradientDrawable().apply { setColor(corInt); cornerRadius = context.dp(8).toFloat() }
                isClickable = true; isFocusable = true
                setOnClickListener { dialog.dismiss(); onClick() }
            }
        }

        root.addView(criarBotao(btnPrincipal, corPrincipal, onPrincipal))
        root.addView(criarBotao(btnSecundario, corSecundario, onSecundario))

        root.addView(TextView(context).apply {
            text = "Voltar"; textSize = 13f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(40)).apply { topMargin = context.dp(2) }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = context.dp(16).toFloat() })
            val p = attributes; p.width = (context.resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
        }
        dialog.show()
    }

    fun mostrarInfo(context: Context, titulo: String, mensagem: String) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#141414")); setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(20))
        }

        root.addView(TextView(context).apply {
            text = titulo; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(12) }
        })

        root.addView(TextView(context).apply {
            text = mensagem; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.5f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(20) }
        })

        root.addView(TextView(context).apply {
            text = "OK"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = context.dp(8).toFloat() }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = context.dp(16).toFloat() })
            val p = attributes; p.width = (context.resources.displayMetrics.widthPixels * 0.82).toInt(); attributes = p
        }
        dialog.show()
    }
}
