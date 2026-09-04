package com.vltv.play

object BandeiraHelper {

    // Mapa: nome em português (lowercase) → emoji de bandeira
    private val bandeiras = mapOf(
        // América do Sul
        "brasil"          to "🇧🇷",
        "argentina"       to "🇦🇷",
        "uruguai"         to "🇺🇾",
        "colombia"        to "🇨🇴",
        "chile"           to "🇨🇱",
        "equador"         to "🇪🇨",
        "peru"            to "🇵🇪",
        "venezuela"       to "🇻🇪",
        "bolivia"         to "🇧🇴",
        "paraguai"        to "🇵🇾",

        // América do Norte e Central
        "estados unidos"  to "🇺🇸",
        "eua"             to "🇺🇸",
        "usa"             to "🇺🇸",
        "mexico"          to "🇲🇽",
        "canada"          to "🇨🇦",
        "costa rica"      to "🇨🇷",
        "panama"          to "🇵🇦",
        "jamaica"         to "🇯🇲",
        "honduras"        to "🇭🇳",
        "el salvador"     to "🇸🇻",
        "guatemala"       to "🇬🇹",
        "cuba"            to "🇨🇺",
        "haiti"           to "🇭🇹",

        // Europa
        "alemanha"        to "🇩🇪",
        "franca"          to "🇫🇷",
        "frança"          to "🇫🇷",
        "espanha"         to "🇪🇸",
        "italia"          to "🇮🇹",
        "itália"          to "🇮🇹",
        "portugal"        to "🇵🇹",
        "holanda"         to "🇳🇱",
        "belgica"         to "🇧🇪",
        "bélgica"         to "🇧🇪",
        "croatia"         to "🇭🇷",
        "croacia"         to "🇭🇷",
        "croácia"         to "🇭🇷",
        "suica"           to "🇨🇭",
        "suíça"           to "🇨🇭",
        "dinamarca"       to "🇩🇰",
        "suecia"          to "🇸🇪",
        "suécia"          to "🇸🇪",
        "noruega"         to "🇳🇴",
        "austria"         to "🇦🇹",
        "áustria"         to "🇦🇹",
        "polonia"         to "🇵🇱",
        "polônia"         to "🇵🇱",
        "hungria"         to "🇭🇺",
        "hungria"         to "🇭🇺",
        "republica tcheca" to "🇨🇿",
        "eslovaquia"      to "🇸🇰",
        "eslovenia"       to "🇸🇮",
        "romenia"         to "🇷🇴",
        "romênia"         to "🇷🇴",
        "servia"          to "🇷🇸",
        "sérvia"          to "🇷🇸",
        "ucrania"         to "🇺🇦",
        "ucrânia"         to "🇺🇦",
        "russia"          to "🇷🇺",
        "rússia"          to "🇷🇺",
        "turquia"         to "🇹🇷",
        "grecia"          to "🇬🇷",
        "grécia"          to "🇬🇷",
        "albania"         to "🇦🇱",
        "albânia"         to "🇦🇱",
        "finlandia"       to "🇫🇮",
        "finlândia"       to "🇫🇮",
        "islandia"        to "🇮🇸",
        "islândia"        to "🇮🇸",
        "irlanda"         to "🇮🇪",

        // Reino Unido (subdivisões — usando emoji UK como fallback universal)
        "inglaterra"      to "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
        "escocia"         to "🏴󠁧󠁢󠁳󠁣󠁴󠁿",
        "escócia"         to "🏴󠁧󠁢󠁳󠁣󠁴󠁿",
        "gales"           to "🏴󠁧󠁢󠁷󠁬󠁳󠁿",
        "país de gales"   to "🏴󠁧󠁢󠁷󠁬󠁳󠁿",
        "irlanda do norte" to "🇬🇧",
        "reino unido"     to "🇬🇧",
        "gra-bretanha"    to "🇬🇧",

        // África
        "marrocos"        to "🇲🇦",
        "nigeria"         to "🇳🇬",
        "nigéria"         to "🇳🇬",
        "senegal"         to "🇸🇳",
        "camaroes"        to "🇨🇲",
        "camarões"        to "🇨🇲",
        "ghana"           to "🇬🇭",
        "gana"            to "🇬🇭",
        "egito"           to "🇪🇬",
        "africa do sul"   to "🇿🇦",
        "áfrica do sul"   to "🇿🇦",
        "tunisia"         to "🇹🇳",
        "tunísia"         to "🇹🇳",
        "argelia"         to "🇩🇿",
        "argélia"         to "🇩🇿",
        "mali"            to "🇲🇱",
        "costa do marfim" to "🇨🇮",
        "tanzania"        to "🇹🇿",
        "quenia"          to "🇰🇪",

        // Ásia e Oceania
        "japao"           to "🇯🇵",
        "japão"           to "🇯🇵",
        "coreia do sul"   to "🇰🇷",
        "coreia do norte" to "🇰🇵",
        "china"           to "🇨🇳",
        "australia"       to "🇦🇺",
        "austrália"       to "🇦🇺",
        "arábia saudita"  to "🇸🇦",
        "arabia saudita"  to "🇸🇦",
        "ira"             to "🇮🇷",
        "irã"             to "🇮🇷",
        "ira"             to "🇮🇷",
        "iraque"          to "🇮🇶",
        "qatar"           to "🇶🇦",
        "catar"           to "🇶🇦",
        "emirados arabes" to "🇦🇪",
        "india"           to "🇮🇳",
        "índia"           to "🇮🇳",
        "indonesia"       to "🇮🇩",
        "indonésia"       to "🇮🇩",
        "nova zelandia"   to "🇳🇿",
        "nova zelândia"   to "🇳🇿",
        "filipinas"       to "🇵🇭",
        "tailandia"       to "🇹🇭",
        "tailândia"       to "🇹🇭",
        "vietna"          to "🇻🇳",
        "vietnã"          to "🇻🇳"
    )

    /**
     * Recebe o título do Firebase (ex: "Brasil x Escócia")
     * e retorna formatado com bandeiras (ex: "🇧🇷  Brasil  ×  Escócia  🏴󠁧󠁢󠁳󠁣󠁴󠁿")
     * Funciona com separadores: "x", "X", "vs", "×"
     */
    fun formatarTituloComBandeiras(titulo: String): String {
        // Detecta o separador (case-insensitive, com espaços opcionais)
        val regex = Regex("""(?i)\s+(x|vs|×)\s+""")
        val match = regex.find(titulo) ?: return titulo  // sem separador → retorna original

        val timeA = titulo.substring(0, match.range.first).trim()
        val timeB = titulo.substring(match.range.last + 1).trim()

        val bandeiraA = resolverBandeira(timeA)
        val bandeiraB = resolverBandeira(timeB)

        return buildString {
            if (bandeiraA != null) append("$bandeiraA  ")
            append(timeA)
            append("  ×  ")
            append(timeB)
            if (bandeiraB != null) append("  $bandeiraB")
        }
    }

    private fun resolverBandeira(nomeTime: String): String? {
        val chave = nomeTime.lowercase().trim()
        // Busca exata primeiro
        bandeiras[chave]?.let { return it }
        // Busca parcial (ex: "Seleção Brasileira" ainda não implementado, mas cobre variantes)
        return bandeiras.entries.firstOrNull { (k, _) -> chave.contains(k) || k.contains(chave) }?.value
    }
}
