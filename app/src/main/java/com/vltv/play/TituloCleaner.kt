package com.vltv.play

/**
 * Fonte única de limpeza de nome "sujo" (tags de qualidade, idioma, grupo de
 * release etc.) usada por DetailsActivity, SeriesDetailsActivity,
 * NovidadesActivity e NovidadesAdapter.
 *
 * Antes desse arquivo existiam 5 versões diferentes dessa lógica espalhadas
 * pelo código, cada uma cobrindo um conjunto diferente de termos — por isso
 * o mesmo título casava com o TMDB numa tela e falhava em outra.
 *
 * ⚠️ Por pedido explícito, o banner da Home (HomeActivity) NÃO usa este
 * arquivo e não deve ser alterado para usá-lo — mexer na lógica de nome do
 * banner já causou regressões anteriores (banner sobrepondo capa errada).
 */
object TituloCleaner {

    // Lista consolidada: junção de TUDO que já existia em DetailsActivity,
    // SeriesDetailsActivity, NovidadesActivity/Adapter, VodActivity e
    // SeriesActivity (8 implementações diferentes ao todo!), mais termos
    // que nenhuma delas cobria.
    private val TAGS_SUJAS = listOf(
        "FHD", "FULL HD", "FULL-HD", "HD", "SD", "4K", "8K", "UHD", "HDR",
        "720P", "1080P", "2160P",
        "H264", "H265", "H.264", "H.265", "X264", "X265", "HEVC",
        "BLURAY", "BLU-RAY", "REMUX", "REPACK",
        "WEB-DL", "WEBRIP", "WEB", "BRRIP", "DVDRIP", "AVI", "MKV", "MP4",
        "HDTV", "HDCAM", "CAM", "TS", "TC", "R5", "SCREENER",
        "DUAL", "DUAL AUDIO", "AUDIO", "5.1", "2.0", "AAC", "LATINO",
        "LEG", "LEG.", "LEGENDADO", "SUBTITLED",
        "DUB", "DUB.", "DUBLADO", "DUBBED",
        "NACIONAL", "BR:", "SP:", "ITA", "ESP", "PT-BR", "PTBR",
        "CINEMA", "LANÇAMENTO", "LANCAMENTO", "EXCLUSIVO",
        "COMPLETO", "COMPLETE"
    )

    // Códigos/palavras de temporada e episódio (S01, S02, E01, S01E01,
    // EP01, TEMPORADA, SEASON) — vêm de nomes de série "sujos"; não fazem
    // sentido em nome de filme, mas removê-los não causa problema nenhum
    // nesse caso.
    private val REGEX_TEMPORADA_EPISODIO = Regex(
        "(?i)\\bS\\d{1,2}(E\\d{1,3})?\\b|\\bE\\d{1,3}\\b|\\bEP\\d{1,3}\\b|\\bTEMPORADA\\b|\\bSEASON\\b"
    )

    // Regex pronta (uma vez só), com \b (borda de palavra) pra não cortar
    // pedaço de palavra normal do título — ordenada da mais longa pra mais
    // curta pra "FULL HD" não sobrar um "HD" solto depois.
    private val REGEX_TAGS = Regex(
        "(?i)\\b(" + TAGS_SUJAS.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) } + ")\\b"
    )

    /**
     * Limpa um nome de stream pra usar como query de busca no TMDB ou pra
     * exibir como fallback de texto — remove parênteses/colchetes, ano
     * solto, e todas as tags sujas conhecidas. Preserva maiúsculas/
     * minúsculas do restante do título.
     */
    fun limparParaBusca(nomeOriginal: String): String {
        var nome = nomeOriginal
        nome = nome.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        nome = nome.replace(Regex("\\b\\d{4}\\b"), "")
        nome = nome.replace(REGEX_TAGS, "")
        nome = nome.replace(REGEX_TEMPORADA_EPISODIO, "")
        nome = nome.replace(Regex("[|_]"), " ")
        return nome.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Normaliza pra comparação/match (banco local, deduplicação): minúsculo,
     * sem acento, sem pontuação — aplicado sempre em cima do resultado de
     * limparParaBusca().
     */
    fun normalizarParaComparacao(nomeOriginal: String): String {
        return limparParaBusca(nomeOriginal)
            .lowercase()
            .replace(Regex("[àáâãäå]"), "a")
            .replace(Regex("[èéêë]"), "e")
            .replace(Regex("[ìíîï]"), "i")
            .replace(Regex("[òóôõö]"), "o")
            .replace(Regex("[ùúûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[ñ]"), "n")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
