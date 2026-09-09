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
    val backdrop_path: String? = null,
    // ✅ rastreamento de temporada/episódio pra detectar quando uma
    // série existente ganha conteúdo novo. Guarda a última temporada/
    // episódio já vistos no TMDB (last_episode_to_air) da última vez que
    // a sincronização rodou — comparando com o valor atual, dá pra saber
    // se subiu temporada nova ou só mais um episódio.
    val tmdb_ultima_temporada: Int = 0,
    val tmdb_ultimo_episodio: Int = 0,
    val is_nova_temporada: Int = 0,
    val is_novo_episodio: Int = 0,
    // ✅ Timestamp (millis) de quando is_nova_temporada/is_novo_episodio
    // foram marcados — usado pra "expirar" o selo depois de alguns dias
    // (ver JANELA_NOVIDADE_MS em HomeActivity), já que esses dois campos
    // não são limpos a cada sincronização (senão nunca dariam tempo de
    // aparecer pro usuário entre um ciclo e outro).
    val tmdb_flag_marcado_em: Long = 0,
    // ✅ Data (ISO yyyy-MM-dd) do próximo episódio já não exibido no
    // TMDB (next_episode_to_air), APENAS quando esse próximo episódio já
    // pertence a uma temporada NOVA — usada pro selo "Nova Temporada Em
    // Breve". Null quando não há nada agendado dentro da janela.
    val tmdb_proxima_temporada_data: String? = null,
    // ✅ NOVO (v14 → v15): Data (ISO yyyy-MM-dd) do próximo episódio
    // quando ele é da MESMA temporada já em andamento — usada pro selo
    // "Novo Episódio Em Breve", separado do campo acima (que é só
    // quando o próximo episódio já é de uma temporada nova). Antes só
    // existia um campo pros dois casos, o que fazia séries com episódio
    // semanal (ex: Reacher) mostrarem "Nova Temporada Em Breve" errado.
    val tmdb_proximo_episodio_data: String? = null
)

// ✅ projeção leve (só os 4 campos necessários) pra checar o progresso
// de temporada/episódio de cada série sem carregar a entidade inteira —
// usado pela sincronização de nova temporada/novo episódio.
data class SeriesTmdbProgresso(
    val series_id: Int,
    val tmdb_id: Int?,
    val tmdb_ultima_temporada: Int,
    val tmdb_ultimo_episodio: Int
)

// ✅ NOVO: projeção leve (só série_id + nome) pra achar séries que ainda
// não têm tmdb_id vinculado — usada pra tentar vincular retroativamente.
// Hoje o tmdb_id só é gravado quando a série aparece no Top10 Netflix ou
// nos "lançamentos" do TMDB (ano ≥ 2025); séries mais antigas que ainda
// lançam episódios novos (ex: Reacher) nunca passam por ali e por isso
// nunca ganhavam tmdb_id — e sem tmdb_id, nunca entravam na checagem de
// temporada/episódio.
data class SeriesNomeBasico(
    val series_id: Int,
    val name: String
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

    // 🔎 DIAGNÓSTICO TEMPORÁRIO
    @Query("SELECT COUNT(*) FROM vod_streams WHERE is_top10 = 1")
    suspend fun contarVodTop10(): Int

    @Query("SELECT COUNT(*) FROM vod_streams WHERE is_novidade = 1")
    suspend fun contarVodNovidade(): Int

    @Query("SELECT COUNT(*) FROM series_streams WHERE is_top10 = 1")
    suspend fun contarSeriesTop10(): Int

    @Query("SELECT COUNT(*) FROM series_streams WHERE is_novidade = 1")
    suspend fun contarSeriesNovidade(): Int

    @Query("SELECT COUNT(*) FROM series_streams WHERE is_nova_temporada = 1")
    suspend fun contarSeriesNovaTemporada(): Int

    @Query("SELECT * FROM vod_streams")
    suspend fun getAllVods(): List<VodEntity>

    @Query("SELECT * FROM vod_streams WHERE category_id = :categoryId ORDER BY added DESC")
    suspend fun getVodsByCategory(categoryId: String): List<VodEntity>

    @Query("SELECT * FROM vod_streams WHERE stream_id = :id LIMIT 1")
    suspend fun getVodByStreamId(id: Int): VodEntity?

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

    // --- nova temporada / novo episódio ---
    @Query("SELECT series_id, tmdb_id, tmdb_ultima_temporada, tmdb_ultimo_episodio FROM series_streams WHERE tmdb_id IS NOT NULL ORDER BY last_modified DESC LIMIT :limite")
    suspend fun getSeriesComTmdbIdParaChecarEpisodios(limite: Int): List<SeriesTmdbProgresso>

    @Query("UPDATE series_streams SET is_nova_temporada = 1, tmdb_ultima_temporada = :temporada, tmdb_ultimo_episodio = :episodio, tmdb_flag_marcado_em = :agora WHERE series_id = :id")
    suspend fun marcarNovaTemporada(id: Int, temporada: Int, episodio: Int, agora: Long)

    @Query("UPDATE series_streams SET is_novo_episodio = 1, tmdb_ultima_temporada = :temporada, tmdb_ultimo_episodio = :episodio, tmdb_flag_marcado_em = :agora WHERE series_id = :id")
    suspend fun marcarNovoEpisodio(id: Int, temporada: Int, episodio: Int, agora: Long)

    @Query("UPDATE series_streams SET tmdb_ultima_temporada = :temporada, tmdb_ultimo_episodio = :episodio WHERE series_id = :id")
    suspend fun atualizarProgressoSemAlerta(id: Int, temporada: Int, episodio: Int)

    @Query("UPDATE series_streams SET tmdb_proxima_temporada_data = :data WHERE series_id = :id")
    suspend fun atualizarProximaTemporada(id: Int, data: String?)

    // ✅ NOVO: "Novo Episódio Em Breve" — mesma temporada em andamento.
    @Query("UPDATE series_streams SET tmdb_proximo_episodio_data = :data WHERE series_id = :id")
    suspend fun atualizarProximoEpisodio(id: Int, data: String?)

    // ✅ NOVO: séries sem tmdb_id ainda — candidatas a vinculação retroativa.
    @Query("SELECT series_id, name FROM series_streams WHERE tmdb_id IS NULL ORDER BY last_modified DESC LIMIT :limite")
    suspend fun getSeriesSemTmdbId(limite: Int): List<SeriesNomeBasico>

    @Query("UPDATE series_streams SET tmdb_id = :tmdbId WHERE series_id = :id")
    suspend fun atualizarTmdbIdSerie(id: Int, tmdbId: Int)

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

    @Query("SELECT * FROM downloads ORDER BY id DESC")
    fun getAllDownloads(): androidx.lifecycle.LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE profile_name = :profileName ORDER BY id DESC")
    fun getDownloadsByProfile(profileName: String): androidx.lifecycle.LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE stream_id = :streamId AND type = :type LIMIT 1")
    suspend fun getDownloadByStreamId(streamId: Int, type: String): DownloadEntity?

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
// DATABASE — version 15 (nova coluna em series_streams:
// tmdb_proximo_episodio_data, separando "novo episódio em breve" de
// "nova temporada em breve", que antes dividiam o mesmo campo)
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
    version = 15,
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
                // ✅ fallbackToDestructiveMigration recria as tabelas por
                // causa da mudança de versão 14→15 (nova coluna
                // tmdb_proximo_episodio_data em "series_streams"). Apaga
                // downloads salvos e o catálogo local, mas ele
                // resincroniza sozinho na próxima abertura do app — mesmo
                // comportamento já aceito nas migrações anteriores.
                .fallbackToDestructiveMigration()
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryExecutor(
                    java.util.concurrent.Executors.newCachedThreadPool()
                )
                .setTransactionExecutor(
                    java.util.concurrent.Executors.newSingleThreadExecutor()
                )
                .build()
        }
    }
}
