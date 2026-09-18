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

    // Lista consolidada: junção de tudo que já existia em DetailsActivity,
    // SeriesDetailsActivity e NovidadesActivity/Adapter, mais tags comuns
    // que nenhuma delas cobria (CAM, TS, BLURAY, WEB-DL, WEBRIP, BRRIP,
    // DVDRIP, HEVC, X264/X265, HDTV, DUAL, NACIONAL, faixas de áudio, etc).
    private val TAGS_SUJAS = listOf(
        "FHD", "FULL HD", "HD", "SD", "4K", "8K", "UHD", "HDR",
        "H264", "H265", "X264", "X265", "HEVC",
        "BLURAY", "BLU-RAY", "WEB-DL", "WEBRIP", "WEB", "BRRIP", "DVDRIP",
        "HDTV", "HDCAM", "CAM", "TS", "R5",
        "DUAL", "DUAL AUDIO", "5.1", "2.0", "AAC",
        "LEG", "LEG.", "LEGENDADO", "SUBTITLED",
        "DUB", "DUB.", "DUBLADO", "DUBBED",
        "NACIONAL", "BR:", "SP:", "ITA", "ESP"
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
