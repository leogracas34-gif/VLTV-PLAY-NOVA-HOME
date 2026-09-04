package com.vltv.play

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * ConfrontoImageHelper
 *
 * Gera dinamicamente uma imagem de fundo estilo "gráfico de transmissão
 * esportiva" para um confronto de futebol: cores reais de cada clube,
 * degradê diagonal, brilho central (glow de verdade via BlurMaskFilter),
 * selo "VS" e escudos com sombra — tudo combinado sobre os escudos dos
 * times (via EscudoHelper), sem precisar subir manualmente uma imagem
 * por jogo.
 *
 * Recursos do visual gerado:
 *  - Cor de cada metade do fundo DERIVADA DA COR REAL DO CLUBE (mapa
 *    CORES_TIMES), com fallback determinístico por hash para qualquer
 *    time fora do mapa — sempre a mesma cor pro mesmo time.
 *  - Degradê diagonal entre as duas cores, com listras de luz sutis
 *    (efeito "gráfico de TV esportiva").
 *  - Brilho central tipo holofote atrás do selo "VS" (glow de verdade,
 *    usando BlurMaskFilter — não é só uma sombra).
 *  - Vinheta (escurecimento na base e nas bordas) para dar contraste a
 *    qualquer texto sobreposto por cima da imagem (título, canal, horário).
 *  - Disco branco com sombra suave atrás de cada escudo.
 *  - Selo central "VS" com anel dourado e glow.
 *  - Escudos posicionados mais para o centro (24%/76% da largura, e
 *    centralizados verticalmente) para o layout continuar bonito mesmo
 *    se o ImageView de destino cortar (CENTER_CROP) uma parte da imagem
 *    por ter uma proporção diferente da gerada.
 *  - Placeholder com a inicial do nome do time quando o escudo não é
 *    encontrado.
 *  - Cantos arredondados opcionais via parâmetro `raioCantos`.
 *
 * O resultado é cacheado em memória por confronto (tamanho + raio
 * incluídos na chave).
 *
 * USO:
 *   lifecycleScope.launch {
 *       val bitmap = ConfrontoImageHelper.gerarImagemFundo("Flamengo", "São Paulo")
 *       imgFundoConfronto.setImageBitmap(bitmap)
 *   }
 *
 * IMPORTANTE SOBRE PROPORÇÃO: se o ImageView de destino tiver uma
 * proporção (largura x altura) muito diferente da usada aqui, o
 * CENTER_CROP vai ampliar a imagem pra cobrir a área toda, fazendo os
 * escudos parecerem "gigantes". Sempre que possível, passe `largura` e
 * `altura` batendo com o tamanho real do ImageView de destino.
 */
object ConfrontoImageHelper {

    // Cache em memória por confronto: "Flamengo|São Paulo|1080x360|0.0" -> Bitmap
    private val cache = ConcurrentHashMap<String, Bitmap?>()

    // Cores oficiais aproximadas dos principais clubes (Série A/B do
    // Brasileirão). Chave normalizada (minúsculo, sem acento). Times fora
    // dessa lista caem no fallback determinístico por hash em corPrincipalTime.
    private val CORES_TIMES: Map<String, Int> = mapOf(
        "flamengo" to Color.parseColor("#C8102E"),
        "sao paulo" to Color.parseColor("#B22222"),
        "palmeiras" to Color.parseColor("#046A38"),
        "corinthians" to Color.parseColor("#1A1A1A"),
        "vasco" to Color.parseColor("#1A1A1A"),
        "vasco da gama" to Color.parseColor("#1A1A1A"),
        "gremio" to Color.parseColor("#0D80C0"),
        "internacional" to Color.parseColor("#C60C0C"),
        "santos" to Color.parseColor("#1A1A1A"),
        "atletico mineiro" to Color.parseColor("#1A1A1A"),
        "atletico-mg" to Color.parseColor("#1A1A1A"),
        "cruzeiro" to Color.parseColor("#003399"),
        "botafogo" to Color.parseColor("#1A1A1A"),
        "fluminense" to Color.parseColor("#7A003C"),
        "bahia" to Color.parseColor("#1560BD"),
        "vitoria" to Color.parseColor("#D2001C"),
        "mirassol" to Color.parseColor("#FFC72C"),
        "coritiba" to Color.parseColor("#006633"),
        "athletico-pr" to Color.parseColor("#A6192E"),
        "athletico paranaense" to Color.parseColor("#A6192E"),
        "chapecoense" to Color.parseColor("#00723F"),
        "remo" to Color.parseColor("#00539F"),
        "sport" to Color.parseColor("#C8102E"),
        "sport recife" to Color.parseColor("#C8102E"),
        "juventude" to Color.parseColor("#007A3D"),
        "fortaleza" to Color.parseColor("#003DA5"),
        "ceara" to Color.parseColor("#1A1A1A"),
        "goias" to Color.parseColor("#009739"),
        "ponte preta" to Color.parseColor("#1A1A1A"),
        "nautico" to Color.parseColor("#C8102E"),
        "londrina" to Color.parseColor("#C8102E"),
        "sao bernardo" to Color.parseColor("#003DA5"),
        "crb" to Color.parseColor("#E4002B"),
        "america mineiro" to Color.parseColor("#006633"),
        "america-mg" to Color.parseColor("#006633"),
        "avai" to Color.parseColor("#00AEEF"),
        "botafogo-sp" to Color.parseColor("#C8102E"),
        "operario-pr" to Color.parseColor("#1A1A1A"),
        "novorizontino" to Color.parseColor("#007A3D"),
        "amazonas" to Color.parseColor("#009739"),
        "volta redonda" to Color.parseColor("#1560BD"),
        "vila nova" to Color.parseColor("#1A1A1A"),
        "cuiaba" to Color.parseColor("#FFC72C"),
        "criciuma" to Color.parseColor("#F5C518"),
        "red bull bragantino" to Color.parseColor("#E4002B"),
        "bragantino" to Color.parseColor("#E4002B")
    )

    /**
     * Gera (ou recupera do cache) a imagem de fundo do confronto entre
     * timeCasa e timeFora.
     *
     * @param timeCasa nome do time mandante (mesmo formato usado no JSON, ex: "Flamengo")
     * @param timeFora nome do time visitante (ex: "São Paulo")
     * @param largura largura do bitmap gerado, em pixels — de preferência
     * batendo com a largura real do ImageView de destino.
     * @param altura altura do bitmap gerado, em pixels — de preferência
     * batendo com a altura real do ImageView de destino, pra evitar que o
     * CENTER_CROP amplie demais a imagem.
     * @param raioCantos raio (em px) para cantos arredondados no bitmap
     * final. Use 0f (padrão) se o card que vai exibir a imagem já
     * arredonda os cantos sozinho.
     * @return Bitmap pronto para uso (ex: ImageView.setImageBitmap). Só
     * retorna null se uma chamada anterior para esse mesmo confronto já
     * tiver falhado de forma irrecuperável (fica marcado em cache).
     */
    suspend fun gerarImagemFundo(
        timeCasa: String,
        timeFora: String,
        largura: Int = 1080,
        altura: Int = 360,
        raioCantos: Float = 0f
    ): Bitmap? {
        val chave = "$timeCasa|$timeFora|${largura}x$altura|$raioCantos"
        cache[chave]?.let { return it }
        if (cache.containsKey(chave)) return null

        // Blindado: uma falha aqui (rede, decodificação de imagem, memória)
        // NUNCA pode subir como exceção não tratada. Como isso costuma
        // rodar dentro de um lifecycleScope, uma exceção não capturada
        // cancelaria o Job inteiro da tela — inclusive outras coroutines
        // que não têm nada a ver com isso (ex: carregamento de VOD/séries).
        return try {
            val bitmapCasa = EscudoHelper.buscarEscudoBitmap(timeCasa)
            val bitmapFora = EscudoHelper.buscarEscudoBitmap(timeFora)

            val resultado = withContext(Dispatchers.Default) {
                desenharConfronto(timeCasa, timeFora, bitmapCasa, bitmapFora, largura, altura, raioCantos)
            }

            cache[chave] = resultado
            resultado
        } catch (e: Throwable) {
            e.printStackTrace()
            cache[chave] = null
            null
        }
    }

    /**
     * Limpa o cache de imagens geradas. Útil se quiser forçar regeneração
     * (ex: após ajustar cores/layout do desenharConfronto em desenvolvimento).
     */
    fun limparCache() {
        cache.clear()
    }

    // ==================== Montagem principal ====================

    private fun desenharConfronto(
        timeCasa: String,
        timeFora: String,
        bitmapCasa: Bitmap?,
        bitmapFora: Bitmap?,
        largura: Int,
        altura: Int,
        raioCantos: Float
    ): Bitmap {
        var resultado = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultado)

        val corCasa = escurecer(corPrincipalTime(timeCasa), 0.55f)
        val corFora = escurecer(corPrincipalTime(timeFora), 0.55f)

        desenharFundoDegrade(canvas, corCasa, corFora, largura, altura)
        desenharListrasDeLuz(canvas, largura, altura)

        val yCentro = altura * 0.46f
        val xCentro = largura / 2f

        desenharGlowCentral(canvas, xCentro, yCentro, altura)
        desenharVinheta(canvas, largura, altura)

        // Escudos mais para o centro (24%/76%): mesmo que o ImageView de
        // destino corte parte da imagem, os dois escudos continuam visíveis.
        val tamanhoEscudo = (altura * 0.5f).toInt()
        val tamanhoDisco = (tamanhoEscudo * 1.22f).toInt()

        val xCasa = largura * 0.24f
        desenharEscudoComDisco(canvas, bitmapCasa, timeCasa, xCasa, yCentro, tamanhoEscudo, tamanhoDisco)

        val xFora = largura * 0.76f
        desenharEscudoComDisco(canvas, bitmapFora, timeFora, xFora, yCentro, tamanhoEscudo, tamanhoDisco)

        desenharSeloVS(canvas, xCentro, yCentro, altura)

        if (raioCantos > 0f) {
            resultado = aplicarCantosArredondados(resultado, raioCantos)
        }

        return resultado
    }

    // ==================== Cores por time ====================

    private fun normalizarNomeTime(nome: String): String {
        return nome.lowercase().trim()
            .replace("á", "a").replace("â", "a").replace("ã", "a")
            .replace("é", "e").replace("ê", "e")
            .replace("í", "i")
            .replace("ó", "o").replace("ô", "o").replace("õ", "o")
            .replace("ú", "u")
            .replace("ç", "c")
    }

    private fun corPrincipalTime(nome: String): Int {
        val chave = normalizarNomeTime(nome)
        CORES_TIMES[chave]?.let { return it }
        CORES_TIMES.entries.firstOrNull { (k, _) -> chave.contains(k) || k.contains(chave) }
            ?.let { return it.value }

        // Fallback determinístico por hash para times fora do mapa: mesmo
        // time sempre gera a mesma cor.
        val hue = ((chave.hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f))
    }

    private fun escurecer(cor: Int, fator: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(cor, hsv)
        hsv[1] = (hsv[1] * 0.92f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * fator).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    // ==================== Fundo ====================

    private fun desenharFundoDegrade(canvas: Canvas, corCasa: Int, corFora: Int, largura: Int, altura: Int) {
        val gradiente = LinearGradient(
            0f, 0f, largura.toFloat(), altura.toFloat(),
            corCasa, corFora,
            Shader.TileMode.CLAMP
        )
        val paintFundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradiente }
        canvas.drawRect(0f, 0f, largura.toFloat(), altura.toFloat(), paintFundo)
    }

    private fun desenharListrasDeLuz(canvas: Canvas, largura: Int, altura: Int) {
        // Listras diagonais bem sutis, tipo gráfico de transmissão esportiva.
        canvas.save()
        canvas.rotate(-18f, largura / 2f, altura / 2f)
        val paintListra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 14
        }
        val larguraListra = largura * 0.05f
        val espacamento = largura * 0.16f
        var x = -largura * 0.3f
        while (x < largura * 1.3f) {
            canvas.drawRect(x, -altura * 0.5f, x + larguraListra, altura * 1.5f, paintListra)
            x += espacamento
        }
        canvas.restore()
    }

    private fun desenharGlowCentral(canvas: Canvas, xCentro: Float, yCentro: Float, altura: Int) {
        // Brilho tipo holofote atrás do selo VS — dá foco visual ao centro
        // da imagem e um acabamento bem mais "profissional" que um fundo liso.
        val raioGlow = altura * 0.55f
        val gradienteGlow = RadialGradient(
            xCentro, yCentro, raioGlow,
            Color.parseColor("#33FFFFFF"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradienteGlow }
        canvas.drawRect(0f, 0f, xCentro * 2f, yCentro * 2f, paintGlow)
    }

    private fun desenharVinheta(canvas: Canvas, largura: Int, altura: Int) {
        // Escurece a base — ajuda a legibilidade de título/canal/horário
        // quando esses textos são sobrepostos por cima da imagem no card.
        val gradienteInferior = LinearGradient(
            0f, altura * 0.4f, 0f, altura.toFloat(),
            Color.TRANSPARENT, Color.parseColor("#C2000000"),
            Shader.TileMode.CLAMP
        )
        val paintInferior = Paint().apply { shader = gradienteInferior }
        canvas.drawRect(0f, 0f, largura.toFloat(), altura.toFloat(), paintInferior)

        // Vinheta radial sutil nas bordas, focando a atenção no centro
        val raioVinheta = largura * 0.7f
        val gradienteRadial = RadialGradient(
            largura / 2f, altura / 2f, raioVinheta,
            Color.TRANSPARENT, Color.parseColor("#4D000000"),
            Shader.TileMode.CLAMP
        )
        val paintRadial = Paint().apply { shader = gradienteRadial }
        canvas.drawRect(0f, 0f, largura.toFloat(), altura.toFloat(), paintRadial)
    }

    // ==================== Escudos ====================

    private fun desenharEscudoComDisco(
        canvas: Canvas,
        bitmap: Bitmap?,
        nomeTime: String,
        xCentro: Float,
        yCentro: Float,
        tamanhoEscudo: Int,
        tamanhoDisco: Int
    ) {
        val raioDisco = tamanhoDisco / 2f

        // Sombra suave por trás do disco (profundidade)
        val paintSombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#40000000")
            setShadowLayer(16f, 0f, 8f, Color.parseColor("#70000000"))
        }
        canvas.drawCircle(xCentro, yCentro, raioDisco, paintSombra)

        // Disco branco com leve degradê radial (dá volume, não fica "chapado")
        val gradienteDisco = RadialGradient(
            xCentro - raioDisco * 0.3f, yCentro - raioDisco * 0.3f, raioDisco * 1.6f,
            Color.parseColor("#FFFFFFFF"), Color.parseColor("#E8E8E8"),
            Shader.TileMode.CLAMP
        )
        val paintDisco = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradienteDisco }
        canvas.drawCircle(xCentro, yCentro, raioDisco, paintDisco)

        val paintBorda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = raioDisco * 0.05f
            color = Color.parseColor("#26000000")
        }
        canvas.drawCircle(xCentro, yCentro, raioDisco, paintBorda)

        if (bitmap != null) {
            val paintEscudo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val escalado = Bitmap.createScaledBitmap(bitmap, tamanhoEscudo, tamanhoEscudo, true)
            val left = xCentro - tamanhoEscudo / 2f
            val top = yCentro - tamanhoEscudo / 2f
            canvas.drawBitmap(escalado, left, top, paintEscudo)
        } else {
            // Escudo não encontrado: em vez de deixar um vão vazio, desenha
            // um selo com a inicial do nome do time, na cor oficial do clube.
            desenharPlaceholderTime(canvas, nomeTime, xCentro, yCentro, tamanhoEscudo)
        }
    }

    private fun desenharPlaceholderTime(canvas: Canvas, nomeTime: String, xCentro: Float, yCentro: Float, tamanho: Int) {
        val inicial = nomeTime.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val corFundo = corPrincipalTime(nomeTime)

        val paintCirculo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = corFundo
            alpha = 230
        }
        canvas.drawCircle(xCentro, yCentro, tamanho / 2.5f, paintCirculo)

        val paintTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = tamanho * 0.5f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(inicial, xCentro, yCentro + paintTexto.textSize * 0.34f, paintTexto)
    }

    // ==================== Selo "VS" central ====================

    private fun desenharSeloVS(canvas: Canvas, xCentro: Float, yCentro: Float, altura: Int) {
        val raio = altura * 0.155f

        // Glow externo de verdade (desfoque real via BlurMaskFilter, não só
        // uma sombra) — dá um acabamento bem mais "premium" ao selo.
        val paintGlowExterno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCFFD700") // dourado suave
            maskFilter = BlurMaskFilter(raio * 0.5f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(xCentro, yCentro, raio * 0.92f, paintGlowExterno)

        val paintFundoSelo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2101010")
            setShadowLayer(12f, 0f, 5f, Color.parseColor("#90000000"))
        }
        canvas.drawCircle(xCentro, yCentro, raio, paintFundoSelo)

        // Anel dourado — remete a selo/medalha de partida
        val paintAnel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = raio * 0.1f
            color = Color.parseColor("#FFD700")
            alpha = 210
        }
        canvas.drawCircle(xCentro, yCentro, raio * 0.88f, paintAnel)

        val paintTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = raio * 0.92f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.03f
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#80000000"))
        }
        canvas.drawText("VS", xCentro, yCentro + paintTexto.textSize * 0.34f, paintTexto)
    }

    // ==================== Cantos arredondados (opcional) ====================

    private fun aplicarCantosArredondados(bitmap: Bitmap, raio: Float): Bitmap {
        val saida = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(saida)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawRoundRect(rect, raio, raio, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return saida
    }
}
