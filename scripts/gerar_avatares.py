#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gerar_avatares.py
------------------
Baixa as capas/pôsteres atuais no TMDB para o catálogo novo de avatares do
VLTV Play, recorta cada imagem em um quadrado centralizado (com viés para o
topo em imagens retrato, onde o personagem costuma estar) e salva os PNGs já
com o nome que o AvatarSelectionDialog.kt espera (av_xxx.png).

USO
---
1) Defina a variável de ambiente TMDB_API_KEY (a mesma chave já usada no app).
2) Rode:  python3 gerar_avatares.py
3) Os arquivos saem na pasta ./saida/av_xxx.png — copie/commite essa pasta
   dentro de app/src/main/res/drawable-nodpi/ no seu repositório.

Este script NÃO precisa de computador: o workflow
.github/workflows/atualizar-avatares.yml roda ele automaticamente no GitHub
Actions e já commita os PNGs no repositório sozinho.
"""

import os
import sys
import time
import requests
from io import BytesIO
from PIL import Image

TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "").strip()
TMDB_SEARCH_URL = "https://api.themoviedb.org/3/search/{tipo}"
TMDB_IMG_BASE = "https://image.tmdb.org/t/p/w780"

PASTA_SAIDA = "saida"
TAMANHO_FINAL = 500  # px, quadrado

# ─── Catálogo: (id_resource, nome_exibicao, titulo_busca_tmdb, tipo, categoria) ──
# tipo: "movie" ou "tv"
CATALOGO = [
    # Marvel
    ("av_spider_man", "Homem-Aranha", "Spider-Man: No Way Home", "movie", "Marvel"),
    ("av_deadpool", "Deadpool", "Deadpool & Wolverine", "movie", "Marvel"),
    ("av_wolverine", "Wolverine", "Deadpool & Wolverine", "movie", "Marvel"),
    ("av_capitao_america", "Capitão América", "Captain America: Brave New World", "movie", "Marvel"),
    ("av_thunderbolts", "Thunderbolts", "Thunderbolts*", "movie", "Marvel"),
    ("av_quarteto_fantastico", "Quarteto Fantástico", "The Fantastic Four: First Steps", "movie", "Marvel"),
    ("av_loki", "Loki", "Loki", "tv", "Marvel"),
    ("av_wanda", "Wanda", "WandaVision", "tv", "Marvel"),
    ("av_venom", "Venom", "Venom: The Last Dance", "movie", "Marvel"),
    ("av_x_men", "X-Men", "X-Men '97", "tv", "Marvel"),

    # DC
    ("av_superman", "Superman", "Superman", "movie", "DC"),
    ("av_batman", "Batman", "The Batman", "movie", "DC"),
    ("av_wonder_woman", "Mulher Maravilha", "Wonder Woman 1984", "movie", "DC"),
    ("av_harley_quinn", "Arlequina", "Harley Quinn", "tv", "DC"),
    ("av_joker", "Coringa", "Joker: Folie à Deux", "movie", "DC"),
    ("av_penguim", "Pinguim", "The Penguin", "tv", "DC"),
    ("av_peacemaker", "Peacemaker", "Peacemaker", "tv", "DC"),
    ("av_supergirl", "Supergirl", "Supergirl: Woman of Tomorrow", "movie", "DC"),
    ("av_lanterna_verde", "Lanterna Verde", "Green Lantern", "movie", "DC"),
    ("av_creature_commandos", "Creature Commandos", "Creature Commandos", "tv", "DC"),

    # Disney / Pixar
    ("av_moana", "Moana", "Moana 2", "movie", "Disney"),
    ("av_elsa", "Elsa", "Frozen II", "movie", "Disney"),
    ("av_asha", "Asha", "Wish", "movie", "Disney"),
    ("av_joy", "Alegria", "Inside Out 2", "movie", "Disney"),
    ("av_mufasa", "Mufasa", "Mufasa: The Lion King", "movie", "Disney"),
    ("av_judy_hopps", "Judy Hopps", "Zootopia 2", "movie", "Disney"),
    ("av_elio", "Elio", "Elio", "movie", "Disney"),
    ("av_stitch", "Stitch", "Lilo & Stitch", "movie", "Disney"),
    ("av_encanto", "Mirabel", "Encanto", "movie", "Disney"),
    ("av_luca", "Luca", "Luca", "movie", "Disney"),

    # Star Wars
    ("av_ahsoka", "Ahsoka", "Ahsoka", "tv", "Star Wars"),
    ("av_mandalorian", "Mandalorian", "The Mandalorian", "tv", "Star Wars"),
    ("av_grogu", "Grogu", "The Mandalorian", "tv", "Star Wars"),
    ("av_andor", "Cassian Andor", "Andor", "tv", "Star Wars"),
    ("av_boba_fett", "Boba Fett", "The Book of Boba Fett", "tv", "Star Wars"),
    ("av_skeleton_crew", "Skeleton Crew", "Star Wars: Skeleton Crew", "tv", "Star Wars"),
    ("av_darth_vader", "Darth Vader", "Obi-Wan Kenobi", "tv", "Star Wars"),
    ("av_rey", "Rey", "Star Wars: The Rise of Skywalker", "movie", "Star Wars"),
    ("av_kylo_ren", "Kylo Ren", "Star Wars: The Rise of Skywalker", "movie", "Star Wars"),
    ("av_acolyte", "The Acolyte", "Star Wars: The Acolyte", "tv", "Star Wars"),

    # Séries
    ("av_stranger_things", "Stranger Things", "Stranger Things", "tv", "Séries"),
    ("av_wednesday", "Wandinha", "Wednesday", "tv", "Séries"),
    ("av_the_last_of_us", "The Last of Us", "The Last of Us", "tv", "Séries"),
    ("av_house_dragon", "House of the Dragon", "House of the Dragon", "tv", "Séries"),
    ("av_the_boys", "The Boys", "The Boys", "tv", "Séries"),
    ("av_squid_game", "Round 6", "Squid Game", "tv", "Séries"),
    ("av_arcane", "Arcane", "Arcane", "tv", "Séries"),
    ("av_bridgerton", "Bridgerton", "Bridgerton", "tv", "Séries"),
    ("av_severance", "Severance", "Severance", "tv", "Séries"),
    ("av_fallout", "Fallout", "Fallout", "tv", "Séries"),

    # Ação
    ("av_john_wick", "John Wick", "John Wick: Chapter 4", "movie", "Ação"),
    ("av_ethan_hunt", "Ethan Hunt", "Mission: Impossible - The Final Reckoning", "movie", "Ação"),
    ("av_dune", "Paul Atreides", "Dune: Part Two", "movie", "Ação"),
    ("av_top_gun", "Top Gun", "Top Gun: Maverick", "movie", "Ação"),
    ("av_gladiador", "Gladiador", "Gladiator II", "movie", "Ação"),
    ("av_furiosa", "Furiosa", "Furiosa: A Mad Max Saga", "movie", "Ação"),
    ("av_f1", "F1", "F1", "movie", "Ação"),
    ("av_venganca", "The Beekeeper", "The Beekeeper", "movie", "Ação"),
    ("av_equalizer", "Equalizer", "The Equalizer 3", "movie", "Ação"),
    ("av_matrix", "Matrix", "The Matrix Resurrections", "movie", "Ação"),

    # Anime
    ("av_gojo", "Gojo Satoru", "Jujutsu Kaisen", "tv", "Anime"),
    ("av_tanjiro", "Tanjiro", "Demon Slayer: Kimetsu no Yaiba", "tv", "Anime"),
    ("av_denji", "Chainsaw Man", "Chainsaw Man", "tv", "Anime"),
    ("av_luffy", "Luffy", "One Piece", "tv", "Anime"),
    ("av_eren", "Eren Jaeger", "Attack on Titan", "tv", "Anime"),
    ("av_anya", "Anya Forger", "Spy x Family", "tv", "Anime"),
    ("av_deku", "Deku", "My Hero Academia", "tv", "Anime"),
    ("av_jinwoo", "Sung Jinwoo", "Solo Leveling", "tv", "Anime"),
    ("av_frieren", "Frieren", "Frieren: Beyond Journey's End", "tv", "Anime"),
    ("av_naruto", "Naruto", "Naruto Shippuden", "tv", "Anime"),

    # Infantil
    ("av_bluey", "Bluey", "Bluey", "tv", "Infantil"),
    ("av_mario", "Mario", "The Super Mario Bros. Movie", "movie", "Infantil"),
    ("av_sonic", "Sonic", "Sonic the Hedgehog 3", "movie", "Infantil"),
    ("av_patrulha_canina", "Patrulha Canina", "Paw Patrol: The Mighty Movie", "movie", "Infantil"),
    ("av_gabby", "Gabby's Dollhouse", "Gabby's Dollhouse", "tv", "Infantil"),
    ("av_turma_monica", "Turma da Mônica", "Turma da Mônica: Lições", "movie", "Infantil"),
    ("av_minions", "Minions", "Minions: The Rise of Gru", "movie", "Infantil"),
    ("av_pj_masks", "PJ Masks", "PJ Masks", "tv", "Infantil"),
    ("av_peppa", "Peppa Pig", "Peppa Pig", "tv", "Infantil"),
    ("av_kung_fu_panda", "Kung Fu Panda", "Kung Fu Panda 4", "movie", "Infantil"),
]


def buscar_poster(titulo: str, tipo: str) -> str | None:
    """Busca o titulo no TMDB e devolve a URL do poster (ou None se não achar)."""
    params = {"api_key": TMDB_API_KEY, "query": titulo, "language": "pt-BR"}
    try:
        r = requests.get(TMDB_SEARCH_URL.format(tipo=tipo), params=params, timeout=15)
        r.raise_for_status()
        resultados = r.json().get("results", [])
        if not resultados:
            return None
        poster_path = resultados[0].get("poster_path")
        if not poster_path:
            return None
        return f"{TMDB_IMG_BASE}{poster_path}"
    except requests.RequestException as e:
        print(f"  [erro na busca] {titulo}: {e}")
        return None


def recortar_quadrado_centralizado(img: Image.Image, top_bias: float = 0.18) -> Image.Image:
    """
    Recorta a imagem em um quadrado centralizado.
    Para imagens retrato (mais alta que larga — o caso comum de pôster),
    aplica um viés para cima, já que o rosto do personagem costuma estar
    no terço superior do pôster.
    """
    w, h = img.size
    lado = min(w, h)
    left = (w - lado) / 2
    if h > w:
        top = (h - lado) * top_bias
    else:
        top = (h - lado) / 2
    box = (left, top, left + lado, top + lado)
    return img.crop(box).resize((TAMANHO_FINAL, TAMANHO_FINAL), Image.LANCZOS)


def main():
    if not TMDB_API_KEY:
        print("ERRO: defina a variável de ambiente TMDB_API_KEY antes de rodar.")
        sys.exit(1)

    os.makedirs(PASTA_SAIDA, exist_ok=True)

    ok, falhas = [], []

    for resource_id, nome, titulo_busca, tipo, categoria in CATALOGO:
        print(f"[{categoria}] {nome} ({titulo_busca}) ...", end=" ")
        url = buscar_poster(titulo_busca, tipo)
        if not url:
            print("NÃO ENCONTRADO")
            falhas.append((resource_id, nome, titulo_busca))
            continue
        try:
            resp = requests.get(url, timeout=20)
            resp.raise_for_status()
            img = Image.open(BytesIO(resp.content)).convert("RGB")
            img_final = recortar_quadrado_centralizado(img)
            destino = os.path.join(PASTA_SAIDA, f"{resource_id}.png")
            img_final.save(destino, "PNG")
            print("ok")
            ok.append(resource_id)
        except Exception as e:
            print(f"ERRO AO BAIXAR: {e}")
            falhas.append((resource_id, nome, titulo_busca))

        time.sleep(0.25)  # respeita o rate limit do TMDB

    print("\n──────────────────────────────")
    print(f"Concluído: {len(ok)}/{len(CATALOGO)} avatares gerados em ./{PASTA_SAIDA}/")
    if falhas:
        print("\nNão encontrados / falharam (ajuste o título de busca manualmente):")
        for resource_id, nome, titulo_busca in falhas:
            print(f"  - {resource_id} ({nome}) — busquei por: \"{titulo_busca}\"")


if __name__ == "__main__":
    main()
