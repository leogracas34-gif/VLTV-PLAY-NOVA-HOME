package com.vltv.play.data

import androidx.room.*
import android.content.Context

// ==========================================
// ENTITIES
// ==========================================

@Entity(tableName = "user_profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    var name: String,
    var imageUrl: String? = null,
    val isKids: Boolean = false
)

@Entity(
    tableName = "live_streams",
    indices = [
        Index(value = ["category_id"]),
        Index(value = ["name"]),
        Index(value = ["category_id", "name"])
    ]
)
data class LiveStreamEntity(
    @PrimaryKey val stream_id: Int,
    val name: String,
    val stream_icon: String?,
    val epg_channel_id: String?,
    val category_id: String
)

@Entity(
    tableName = "vod_streams",
    indices = [
        Index(value = ["added"]),
        Index(value = ["category_id"]),
        Index(value = ["name"]),
        Index(value = ["category_id", "added"]),
        Index(value = ["category_id", "name"]),
        Index(value = ["is_top10"]),
        Index(value = ["is_novidade"])
    ]
)
data class VodEntity(
    @PrimaryKey val stream_id: Int,
    val name: String,
    val title: String?,
    val stream_icon: String?,
    val container_extension: String?,
    val rating: String?,
    val category_id: String,
    val added: Long,
    val logo_url: String? = null,
    val tmdb_rank: Int = 0,
    val tmdb_release_date: String? = null,
    val is_top10: Int = 0,
    val is_novidade: Int = 0,
    val tmdb_id: Int? = null,
    val backdrop_path: String? = null
)

@Entity(
    tableName = "series_streams",
    indices = [
        Index(value = ["last_modified"]),
        Index(value = ["category_id"]),
        Index(value = ["name"]),
        Index(value = ["category_id", "last_modified"]),
        Index(value = ["category_id", "name"]),
        Index(value = ["is_top10"]),
        Index(value = ["is_novidade"])
    ]
)
data class SeriesEntity(
    @PrimaryKey val series_id: Int,
    val name: String,
    val cover: String?,
    val rating: String?,
    val category_id: String,
    val last_modified: Long,
    val logo_url: String? = null,
    val tmdb_rank: Int = 0,
    val tmdb_release_date: String? = null,
    val is_top10: Int = 0,
    val is_novidade: Int = 0,
    val tmdb_id: Int? = null,
    val backdrop_path: String? = null
)

@Entity(
    tableName = "watch_history",
    primaryKeys = ["stream_id", "profile_name"],
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["profile_name", "timestamp"])
    ]
)
data class WatchHistoryEntity(
    val stream_id: Int,
    val profile_name: String,
    val name: String,
    val icon: String?,
    val last_position: Long,
    val duration: Long,
    val is_series: Boolean,
    val timestamp: Long
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val category_id: String,
    val category_name: String,
    val type: String  // "vod", "series", "live"
)

@Entity(tableName = "epg_cache", indices = [Index(value = ["stream_id"])])
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val stream_id: String,
    val title: String?,
    val start: String?,
    val stop: String?,
    val description: String?
)

// ✅ (v11 → v12): campo "profile_name" — cada download fica amarrado ao
// perfil que o iniciou (ex: "Infantil" ou o nome do perfil adulto). Antes
// esse campo não existia, então TODOS os downloads apareciam pra TODOS os
// perfis (bug relatado pelo Léo: download feito no Kids aparecendo no
// perfil adulto e vice-versa).
//
// Segue o mesmo padrão já usado em "watch_history.profile_name" — mesma
// convenção de nome de coluna, mesmo jeito de guardar (string simples
// com o nome do perfil, sem precisar de FK pra manter simplicidade).
//
// ✅ (v12 → v13): adicionados índices em "android_download_id",
// "file_path" e "stream_id"+"type" — colunas usadas em
// updateDownloadProgress, updateDownloadProgressByContentId e
// getDownloadByStreamId, que antes não tinham índice próprio (só existia
// em status/name+season/profile_name). Sem efeito perceptível hoje (a
// tabela costuma ter poucas linhas), é só margem de segurança caso o
// usuário acumule muitos downloads ao longo do tempo.
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["name", "season"]),
        Index(value = ["profile_name"]),
        Index(value = ["android_download_id"]),
        Index(value = ["file_path"]),
        Index(value = ["stream_id", "type"])
    ]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val android_download_id: Long = 0L,
    val stream_id: Int,
    val name: String,
    val episode_name: String?,
    val image_url: String?,
    val file_path: String,
    val download_url: String = "",
    val type: String,
    val status: String,
    val progress: Int = 0,
    val total_size: String = "0MB",
    val season: Int = 0,
    val profile_name: String = ""
)

// ==========================================
// DAO
// ==========================================

@Dao
interface StreamDao {

    // --- PERFIS ---
    @Query("SELECT * FROM user_profiles")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity)

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("DELETE FROM user_profiles")
    suspend fun deleteAllProfiles()

    // --- LIVE ---
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveStreams(streams: List<LiveStreamEntity>)

    @Query("SELECT * FROM live_streams WHERE name LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchLive(query: String): List<LiveStreamEntity>

    @Query("DELETE FROM live_streams")
    suspend fun clearLive()

    @Query("SELECT * FROM live_streams WHERE category_id = :categoryId ORDER BY name ASC")
    suspend fun getLiveByCategory(categoryId: String): List<LiveStreamEntity>

    // --- VOD ---
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodStreams(streams: List<VodEntity>)

    @Query("SELECT COUNT(*) FROM vod_streams")
    suspend fun getVodCount(): Int

    @Query("SELECT * FROM vod_streams")
    suspend fun getAllVods(): List<VodEntity>

    @Query("SELECT * FROM vod_streams WHERE category_id = :categoryId ORDER BY added DESC")
    suspend fun getVodsByCategory(categoryId: String): List<VodEntity>

    @Transaction
    @Query("SELECT * FROM vod_streams ORDER BY added DESC LIMIT :limit")
    suspend fun getRecentVods(limit: Int): List<VodEntity>

    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchVod(query: String): List<VodEntity>

    @Query("SELECT * FROM vod_streams WHERE category_id = :categoryId AND name LIKE '%' || :query || '%' LIMIT 50")
    suspend fun searchVodInCategory(categoryId: String, query: String): List<VodEntity>

    @Query("UPDATE vod_streams SET logo_url = :logoUrl WHERE stream_id = :id")
    suspend fun updateVodLogo(id: Int, logoUrl: String)

    @Query("UPDATE vod_streams SET tmdb_id = :tmdbId, backdrop_path = :backdropPath, logo_url = :logoUrl WHERE stream_id = :id")
    suspend fun updateVodTmdbAssets(id: Int, tmdbId: Int?, backdropPath: String?, logoUrl: String?)

    @Query("SELECT * FROM vod_streams ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomVods(limit: Int): List<VodEntity>

    @Transaction
    suspend fun updateVodLogos(updates: Map<Int, String>) {
        updates.forEach { (id, url) -> updateVodLogo(id, url) }
    }

    @Query("SELECT * FROM vod_streams WHERE is_top10 = 1 ORDER BY tmdb_rank ASC LIMIT 10")
    suspend fun getTop10Vods(): List<VodEntity>

    @Query("SELECT * FROM vod_streams WHERE is_novidade = 1 ORDER BY tmdb_release_date DESC LIMIT 20")
    suspend fun getNovidadesVods(): List<VodEntity>

    @Query("UPDATE vod_streams SET tmdb_rank = :rank, is_top10 = 1 WHERE stream_id = :id")
    suspend fun updateVodTop10(id: Int, rank: Int)

    @Query("UPDATE vod_streams SET is_novidade = 1, tmdb_release_date = :releaseDate WHERE stream_id = :id")
    suspend fun updateVodNovidade(id: Int, releaseDate: String)

    @Query("UPDATE vod_streams SET is_top10 = 0, tmdb_rank = 0")
    suspend fun clearVodTop10Flags()

    @Query("UPDATE vod_streams SET is_novidade = 0, tmdb_release_date = NULL")
    suspend fun clearVodNovidadeFlags()

    // --- SÉRIES ---
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesStreams(series: List<SeriesEntity>)

    @Query("SELECT * FROM series_streams")
    suspend fun getAllSeries(): List<SeriesEntity>

    @Query("SELECT * FROM series_streams WHERE category_id = :categoryId ORDER BY last_modified DESC")
    suspend fun getSeriesByCategory(categoryId: String): List<SeriesEntity>

    @Transaction
    @Query("SELECT * FROM series_streams ORDER BY last_modified DESC LIMIT :limit")
    suspend fun getRecentSeries(limit: Int): List<SeriesEntity>

    @Query("UPDATE series_streams SET logo_url = :logoUrl WHERE series_id = :id")
    suspend fun updateSeriesLogo(id: Int, logoUrl: String)

    @Query("UPDATE series_streams SET tmdb_id = :tmdbId, backdrop_path = :backdropPath, logo_url = :logoUrl WHERE series_id = :id")
    suspend fun updateSeriesTmdbAssets(id: Int, tmdbId: Int?, backdropPath: String?, logoUrl: String?)

    @Query("SELECT * FROM series_streams ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomSeries(limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM series_streams WHERE is_top10 = 1 ORDER BY tmdb_rank ASC LIMIT 10")
    suspend fun getTop10Series(): List<SeriesEntity>

    @Query("SELECT * FROM vod_streams WHERE name LIKE :query LIMIT 1")
    suspend fun searchVodByName(query: String): VodEntity?

    @Query("SELECT * FROM series_streams WHERE name LIKE :query LIMIT 1")
    suspend fun searchSeriesByName(query: String): SeriesEntity?

    @Query("SELECT * FROM series_streams WHERE is_novidade = 1 ORDER BY tmdb_release_date DESC LIMIT 20")
    suspend fun getNovidadesSeries(): List<SeriesEntity>

    @Query("UPDATE series_streams SET tmdb_rank = :rank, is_top10 = 1 WHERE series_id = :id")
    suspend fun updateSeriesTop10(id: Int, rank: Int)

    @Query("UPDATE series_streams SET is_novidade = 1, tmdb_release_date = :releaseDate WHERE series_id = :id")
    suspend fun updateSeriesNovidade(id: Int, releaseDate: String)

    @Query("UPDATE series_streams SET is_top10 = 0, tmdb_rank = 0")
    suspend fun clearSeriesTop10Flags()

    @Query("UPDATE series_streams SET is_novidade = 0, tmdb_release_date = NULL")
    suspend fun clearSeriesNovidadeFlags()

    // --- CATEGORIAS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY rowid ASC")
    suspend fun getCategoriesByType(type: String): List<CategoryEntity>

    @Query("DELETE FROM categories WHERE type = :type")
    suspend fun deleteCategoriesByType(type: String)

    // --- HISTÓRICO ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWatchHistory(history: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history WHERE profile_name = :profile ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getWatchHistory(profile: String, limit: Int = 20): List<WatchHistoryEntity>

    // --- DOWNLOADS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity): Long

    // ⚠️ Mantida por compatibilidade, mas NÃO deve mais ser usada pelas
    // telas de listagem (Kids e Adulto), já que retorna downloads de
    // TODOS os perfis misturados — era essa a causa do bug relatado.
    @Query("SELECT * FROM downloads ORDER BY id DESC")
    fun getAllDownloads(): androidx.lifecycle.LiveData<List<DownloadEntity>>

    // ✅ Usar esta em vez de getAllDownloads() nas telas de listagem —
    // filtra pelo perfil que fez o download. Usada tanto por
    // DownloadsActivity (perfil adulto) quanto por KidsDownloadsActivity/
    // KidsSeriesEpisodesActivity (perfil Kids), cada uma passando o nome
    // do próprio perfil ativo.
    @Query("SELECT * FROM downloads WHERE profile_name = :profileName ORDER BY id DESC")
    fun getDownloadsByProfile(profileName: String): androidx.lifecycle.LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE stream_id = :streamId AND type = :type LIMIT 1")
    suspend fun getDownloadByStreamId(streamId: Int, type: String): DownloadEntity?

    // ✅ NOVO: versão da consulta acima que também filtra por perfil —
    // evita ambiguidade quando o MESMO filme/episódio foi baixado tanto
    // pelo perfil adulto quanto pelo perfil Kids (cada um com sua própria
    // linha na tabela). Usada pela KidsMovieDownloadActivity.
    @Query("SELECT * FROM downloads WHERE stream_id = :streamId AND type = :type AND profile_name = :profileName LIMIT 1")
    suspend fun getDownloadByStreamIdAndProfile(streamId: Int, type: String, profileName: String): DownloadEntity?

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE android_download_id = :downloadId")
    suspend fun updateDownloadProgress(downloadId: Long, status: String, progress: Int)

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE file_path = :contentId")
    suspend fun updateDownloadProgressByContentId(contentId: String, status: String, progress: Int)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: Int)

    @Query("DELETE FROM downloads WHERE android_download_id = :downloadId")
    suspend fun deleteDownloadByAndroidId(downloadId: Long)

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getDownloadsByStatus(status: String): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'BAIXANDO'")
    fun getCountDownloadsAtivos(): androidx.lifecycle.LiveData<Int>

    @Query("DELETE FROM downloads")
    suspend fun deleteAllDownloads()

    // ✅ Usar nas telas de "Limpar tudo" para apagar só os downloads do
    // perfil atual, sem afetar os downloads de outros perfis no mesmo
    // aparelho (ex: apagar tudo no Kids não mexe nos downloads do adulto).
    @Query("DELETE FROM downloads WHERE profile_name = :profileName")
    suspend fun deleteAllDownloadsByProfile(profileName: String)

    @Query("SELECT * FROM downloads WHERE name = :seriesName AND season = :season AND type = 'series'")
    suspend fun getDownloadsBySeason(seriesName: String, season: Int): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE name = :seriesName AND season = :season AND type = 'series'")
    suspend fun deleteDownloadsBySeason(seriesName: String, season: Int)

    @Query("SELECT * FROM downloads WHERE name = :seriesName AND type = 'series' ORDER BY season ASC, id ASC")
    suspend fun getDownloadsBySeriesName(seriesName: String): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE name = :seriesName AND type = 'series'")
    suspend fun deleteDownloadsBySeries(seriesName: String)
}

// ==========================================
// DATABASE — version 13 (novos índices em downloads: android_download_id,
// file_path, stream_id+type — antes só havia índice em status/
// name+season/profile_name)
// ==========================================

@Database(
    entities = [
        LiveStreamEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        CategoryEntity::class,
        EpgEntity::class,
        WatchHistoryEntity::class,
        DownloadEntity::class,
        ProfileEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun streamDao(): StreamDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "vltv_play_db"
            )
                // ✅ fallbackToDestructiveMigration recria as tabelas automaticamente
                // por causa da mudança de versão 12→13 (novos índices em
                // "downloads"). Isso apaga downloads salvos localmente (o
                // usuário vai precisar baixar de novo o que já tinha baixado)
                // e o catálogo (vod_streams/series_streams), mas o catálogo
                // resincroniza sozinho na próxima abertura do app — mesmo
                // comportamento já aceito nas migrações anteriores (v9→v10,
                // v10→v11, v11→v12).
                .fallbackToDestructiveMigration()
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // ✅ Esse queryExecutor é compartilhado por TODAS as queries
                // suspend do Room no app inteiro (Home, catálogo VOD/séries,
                // categorias, histórico, downloads...) e também pela
                // reconsulta/emissão do LiveData quando uma tabela muda.
                .setQueryExecutor(
                    java.util.concurrent.Executors.newCachedThreadPool()
                )
                // ✅ CORREÇÃO REAL do "sumiço" da tela de Downloads por
                // 10-15s: até agora, o Room nunca teve um transactionExecutor
                // próprio configurado aqui. Sem isso, o Room usa
                // silenciosamente o próprio queryExecutor também como
                // executor de transação — ou seja, leituras e escritas
                // competiam pelo MESMO pool de threads.
                //
                // O problema: toda vez que o SyncManager insere o catálogo
                // (VOD/séries/canais ao vivo, em lotes de 200 — inclusive na
                // sincronização automática que roda sozinha a cada 10
                // minutos, em segundo plano), cada lote é uma transação. O
                // SQLite só permite UM escritor por vez, então uma thread do
                // pool fica presa esperando esse lock a cada lote — e como
                // esse mesmo pool também atende a consulta de Downloads
                // (LiveData), a tela podia ficar sem thread livre pra
                // reconsultar bem na hora do clique.
                //
                // Um executor de transação dedicado (thread única, só pra
                // escritas) não elimina a regra do SQLite de 1 escritor por
                // vez — isso é do banco, não dá pra burlar — mas isola esse
                // gargalo do pool de leitura, e cada transação em lote de
                // 200 itens é rápida (milissegundos), então a fila de espera
                // do clique passa a ser curta, não mais os 10-15s inteiros
                // de uma sincronização completa.
                .setTransactionExecutor(
                    java.util.concurrent.Executors.newSingleThreadExecutor()
                )
                .build()
        }
    }
}
