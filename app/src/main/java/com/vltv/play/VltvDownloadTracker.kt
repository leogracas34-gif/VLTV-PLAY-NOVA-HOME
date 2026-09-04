package com.vltv.play.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors

// ────────────────────────────────────────────────────────────────
// Ponto único de acesso ao DownloadManager e ao SimpleCache do Media3.
// Precisa ser singleton porque o Media3 exige que só exista UMA instância
// de DownloadManager (e do SimpleCache que ele usa) durante toda a vida
// do processo — se você criar duas instâncias apontando pro mesmo
// diretório, o app quebra com erro de lock de arquivo (IllegalStateException:
// "Another SimpleCache instance uses the folder").
//
// O SimpleCache é onde os bytes baixados realmente ficam gravados no
// disco (dentro de getExternalFilesDir, sem precisar de permissões de
// armazenamento). O DownloadManager é quem orquestra o download em si
// (fila, progresso, retomada em caso de queda de conexão, etc).
// ────────────────────────────────────────────────────────────────
@UnstableApi
object VltvDownloadTracker {

    private const val DOWNLOAD_CONTENT_DIRECTORY = "vltv_media3_downloads"
    private const val USER_AGENT = "IPTVSmartersPro" // mesmo User-Agent já usado no PlayerActivity

    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var downloadCache: SimpleCache? = null
    @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null

    private fun getDatabaseProvider(context: Context): StandaloneDatabaseProvider {
        return databaseProvider ?: synchronized(this) {
            databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { databaseProvider = it }
        }
    }

    // Factory de conexão HTTP usada TANTO pra baixar quanto pra reproduzir
    // (mesma config de headers/timeout que o PlayerActivity já usa hoje
    // pro streaming online).
    fun getHttpDataSourceFactory(): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
    }

    fun getDownloadCache(context: Context): SimpleCache {
        return downloadCache ?: synchronized(this) {
            downloadCache ?: run {
                val appContext = context.applicationContext
                val dir = File(appContext.getExternalFilesDir(null), DOWNLOAD_CONTENT_DIRECTORY)
                if (!dir.exists()) dir.mkdirs()
                SimpleCache(dir, NoOpCacheEvictor(), getDatabaseProvider(appContext))
                    .also { downloadCache = it }
            }
        }
    }

    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: synchronized(this) {
            downloadManager ?: run {
                val appContext = context.applicationContext
                DownloadManager(
                    appContext,
                    getDatabaseProvider(appContext),
                    getDownloadCache(appContext),
                    getHttpDataSourceFactory(),
                    Executors.newFixedThreadPool(3)
                ).also {
                    it.maxParallelDownloads = 3
                    downloadManager = it
                }
            }
        }
    }
}
