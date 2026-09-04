package com.vltv.play

// ────────────────────────────────────────────────────────────────
// Centraliza a configuração da VPS usada como CDN/proxy de imagens do
// TMDB (em vez de bater direto em image.tmdb.org). Ter isso num lugar só
// evita a URL da VPS espalhada em vários arquivos — se um dia você trocar
// de domínio, servidor ou adicionar um segundo espelho, muda só aqui.
// ────────────────────────────────────────────────────────────────
object VpsConfig {

    // Troque aqui se um dia mudar de domínio/CDN.
    const val TMDB_IMAGE_BASE = "https://cdn.vltvplay.tech"

    /**
     * Monta a URL completa de uma imagem do TMDB através da sua VPS.
     * @param path o "file_path" que a API do TMDB retorna (ex: "abc123.jpg"
     *             ou "/abc123.jpg" — funciona com ou sem a barra inicial)
     * @param size o tamanho da imagem TMDB (ex: "original", "w500", "w342", "w1280")
     */
    fun tmdbImage(path: String, size: String = "original"): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$TMDB_IMAGE_BASE/t/p/$size$cleanPath"
    }
}
