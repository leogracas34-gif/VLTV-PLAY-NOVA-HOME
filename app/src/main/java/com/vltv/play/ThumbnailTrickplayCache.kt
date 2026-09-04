package com.vltv.play

import android.graphics.Bitmap

/**
 * ═══════════════════════════════════════════════════════════════════
 *  CACHE DE MINIATURAS PRÉ-GERADAS (estilo "trickplay" da Netflix)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Por que isso existe: decodificar um frame de vídeo NA HORA que o
 * usuário arrasta o dedo (seja via MediaMetadataRetriever numa URL de
 * rede, seja via o player-fantasma offline) sempre vai ter atraso —
 * às vezes de segundos, especialmente pulando pra longe da posição
 * atual. A Netflix não faz isso: eles geram as miniaturas no servidor
 * com antecedência e o app só exibe a imagem certa por índice.
 *
 * Como o VLTV+ não tem esse gerador no backend, a solução no cliente é
 * chegar o mais perto disso possível: assim que o vídeo começa a tocar,
 * decodificamos em segundo plano (sem travar nada) várias miniaturas
 * espalhadas pelo filme/episódio inteiro e guardamos aqui, em memória,
 * já pequenas. Quando o usuário arrasta, em vez de decodificar na hora,
 * a gente só busca a miniatura mais próxima daquele ponto — isso é
 * instantâneo (é só uma busca em lista, não decodificação de vídeo).
 *
 * ✅ CORRIGIDO: antes as miniaturas eram guardadas na ordem em que
 * chegavam (sempre crescente, do início pro fim do vídeo). Isso fazia
 * um ponto distante (ex.: 1h40 de um filme de 2h) nunca ficar pronto a
 * tempo, porque a geração ainda estava "caminhando" lá do início. Agora
 * a grade de posições inteira é conhecida DESDE O COMEÇO (definida em
 * iniciarNovaGeracao), e cada miniatura é guardada direto no lugar dela
 * na grade — não importa a ordem de chegada. Isso permite que quem gera
 * as miniaturas (PlayerActivity) preencha primeiro os pontos espalhados
 * pelo vídeo inteiro (meio, depois quartos, depois oitavos...) em vez
 * de sempre do início pro fim — assim, em poucos segundos já existe
 * cobertura de ponta a ponta, só ficando mais densa com o tempo.
 *
 * Se o usuário arrastar pra um ponto cuja miniatura exata ainda não
 * ficou pronta, buscarMaisProximo() devolve a miniatura JÁ PRONTA mais
 * próxima dali (mesmo que não seja a mais próxima "no papel") — melhor
 * mostrar uma cena um pouco distante do que a tela preta.
 */
class ThumbnailTrickplayCache {

    @Volatile
    private var generationId = 0

    private var timestamps: LongArray = LongArray(0)
    private var bitmaps: Array<Bitmap?> = arrayOfNulls(0)

    /**
     * Abre uma nova "geração" de miniaturas com a grade de posições
     * (em ms, sempre crescente) que vai ser preenchida aos poucos.
     * Descarta qualquer miniatura antiga. Chamar sempre que um novo
     * conteúdo (ou uma nova tentativa de servidor) começar a pré-gerar
     * miniaturas.
     */
    @Synchronized
    fun iniciarNovaGeracao(posicoesOrdenadas: List<Long>): Int {
        limparInterno()
        timestamps = posicoesOrdenadas.toLongArray()
        bitmaps = arrayOfNulls(timestamps.size)
        generationId++
        return generationId
    }

    /** Id da geração atual — usado por quem está gerando os frames pra saber se a própria geração ainda é a válida. */
    fun idAtual(): Int = generationId

    /**
     * Guarda um frame já pronto (pequeno) no lugar certo da grade. Se a
     * geração informada (id) não for mais a atual, ou se a posição não
     * pertencer à grade atual, o bitmap é descartado silenciosamente.
     */
    @Synchronized
    fun definirFrame(id: Int, positionMs: Long, bitmap: Bitmap) {
        if (id != generationId) {
            try { bitmap.recycle() } catch (e: Exception) { /* silencioso */ }
            return
        }
        val indice = indiceExato(positionMs)
        if (indice < 0) {
            try { bitmap.recycle() } catch (e: Exception) { /* silencioso */ }
            return
        }
        bitmaps[indice]?.let {
            try { it.recycle() } catch (e: Exception) { /* silencioso */ }
        }
        bitmaps[indice] = bitmap
    }

    private fun indiceExato(positionMs: Long): Int {
        var lo = 0
        var hi = timestamps.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            when {
                timestamps[mid] == positionMs -> return mid
                timestamps[mid] < positionMs -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    /**
     * Busca a miniatura mais próxima (já pronta) de uma posição (em
     * ms). Primeiro acha qual seria o ponto da grade mais próximo
     * daquela posição; se ele ainda não tiver sido gerado, expande a
     * busca pros vizinhos (dos dois lados) até achar o mais próximo que
     * já esteja pronto. Retorna null só se NADA na grade tiver sido
     * gerado ainda.
     */
    @Synchronized
    fun buscarMaisProximo(positionMs: Long): Bitmap? {
        val tamanho = timestamps.size
        if (tamanho == 0) return null

        val indicePreferido = when {
            positionMs <= timestamps[0] -> 0
            positionMs >= timestamps[tamanho - 1] -> tamanho - 1
            else -> {
                var lo = 0
                var hi = tamanho - 1
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (timestamps[mid] < positionMs) lo = mid + 1 else hi = mid
                }
                val depois = lo
                val antes = (lo - 1).coerceAtLeast(0)
                val diffAntes = Math.abs(positionMs - timestamps[antes])
                val diffDepois = Math.abs(timestamps[depois] - positionMs)
                if (diffAntes <= diffDepois) antes else depois
            }
        }

        bitmaps[indicePreferido]?.let { return it }

        // Ainda não gerou exatamente esse ponto — expande pros vizinhos
        // dos dois lados até achar o mais próximo já pronto.
        var passo = 1
        while (indicePreferido - passo >= 0 || indicePreferido + passo < tamanho) {
            val esq = indicePreferido - passo
            val dir = indicePreferido + passo
            if (esq >= 0) bitmaps[esq]?.let { return it }
            if (dir < tamanho) bitmaps[dir]?.let { return it }
            passo++
        }
        return null
    }

    @Synchronized
    fun temAlgumFrame(): Boolean = bitmaps.any { it != null }

    /** Libera todos os bitmaps guardados. Chamar ao trocar de conteúdo e ao fechar a tela do player. */
    @Synchronized
    fun limpar() {
        limparInterno()
    }

    private fun limparInterno() {
        for (b in bitmaps) {
            try { b?.recycle() } catch (e: Exception) { /* silencioso */ }
        }
        timestamps = LongArray(0)
        bitmaps = arrayOfNulls(0)
    }
}
