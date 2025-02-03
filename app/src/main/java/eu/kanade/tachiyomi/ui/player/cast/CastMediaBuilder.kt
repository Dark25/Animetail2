package eu.kanade.tachiyomi.ui.player.cast

import android.content.Intent
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.data.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.util.LocalHttpServerHolder
import eu.kanade.tachiyomi.util.LocalHttpServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder

class CastMediaBuilder(
    private val viewModel: PlayerViewModel,
    private val activity: PlayerActivity,
) {

    companion object {
        private const val TAG = "CastMediaBuilder"
    }

    private val player by lazy { activity.player }
    private val prefserver: LocalHttpServerHolder by injectLazy()
    private val port = prefserver.port().get()

    init {
        // Configuración de logs para FFmpegKit/FFprobeKit
        FFmpegKitConfig.enableLogCallback { log ->
            logcat(LogPriority.VERBOSE, TAG) { "[FFmpeg] ${log.message}" }
        }
    }

    suspend fun buildMediaInfo(index: Int): MediaInfo = withContext(Dispatchers.IO) {
        val video = viewModel.videoList.value.getOrNull(index)
            ?: throw IllegalStateException("Índice de video inválido: $index")

        val videoUrl = processVideoUrl(video)
        logcat(LogPriority.INFO, TAG) { "URL final del video: $videoUrl" }

        MediaInfo.Builder(videoUrl).apply {
            setContentType(determineContentType(videoUrl))
            setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            addCustomMetadata()
            // Extraemos y configuramos los tracks de forma concurrente
            addMediaTracks(videoUrl)
            setStreamDuration((player.duration ?: 0).toLong() * 1000)
        }.build().also {
            logcat(LogPriority.DEBUG, TAG) { "MediaInfo construido con ${it.mediaTracks?.size ?: 0} tracks" }
        }
    }

    private suspend fun processVideoUrl(video: Video): String {
        return when {
            video.videoUrl!!.startsWith("content://") ->
                getLocalServerUrl(video.videoUrl!!).also { verifyUrlAccessibility(it) }
            video.videoUrl!!.startsWith("magnet") || video.videoUrl!!.endsWith(".torrent") ->
                handleTorrent(video.videoUrl!!, video.quality).also { verifyUrlAccessibility(it) }
            else -> video.videoUrl!!.also { verifyUrlAccessibility(it) }
        }
    }

    private suspend fun verifyUrlAccessibility(url: String) {
        if (!checkUrlAccessibility(url)) {
            throw IllegalStateException("URL no accesible: $url")
        }
    }

    private suspend fun checkUrlAccessibility(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "HEAD"
                connectTimeout = 10000
                readTimeout = 10000
            }
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, TAG) { "Error verificando URL: ${e.javaClass.simpleName} - ${e.message}" }
            false
        }
    }

    private fun determineContentType(url: String): String {
        return when {
            url.contains(".m3u8") -> "application/x-mpegURL"
            url.contains(".mpd") -> "application/dash+xml"
            else -> "video/mp4"
        }
    }

    private fun handleTorrent(url: String, quality: String): String {
        return if (url.startsWith("content://")) {
            handleContentUriTorrent(url, quality)
        } else {
            handleMagnetOrTorrent(url, quality)
        }
    }

    private fun handleContentUriTorrent(url: String, quality: String): String {
        val inputStream = activity.contentResolver.openInputStream(Uri.parse(url))
            ?: throw IllegalStateException("No se pudo abrir el torrent desde content URI")
        val torrent = TorrentServerApi.uploadTorrent(inputStream, quality, "", "", false)
        return TorrentServerUtils.getTorrentPlayLink(torrent, 0)
    }

    private fun handleMagnetOrTorrent(url: String, quality: String): String {
        val index = extractTorrentIndex(url)
        val torrent = TorrentServerApi.addTorrent(url, quality, "", "", false)
        return TorrentServerUtils.getTorrentPlayLink(torrent, index)
    }

    private fun extractTorrentIndex(url: String): Int {
        return try {
            url.substringAfter("index=").substringBefore("&").toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun MediaInfo.Builder.addCustomMetadata(): MediaInfo.Builder {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            viewModel.currentAnime.value?.let { anime ->
                putString(MediaMetadata.KEY_TITLE, anime.title ?: "Sin título")
                anime.thumbnailUrl?.let { url ->
                    addImage(WebImage(Uri.parse(url)))
                }
            }
        }
        return setMetadata(metadata)
    }

    /**
     * Extrae las pistas (audio y subtítulos) de forma concurrente.
     */
    private suspend fun MediaInfo.Builder.addMediaTracks(
        videoUrl: String,
    ): MediaInfo.Builder = withContext(Dispatchers.IO) {
        val subtitleDeferred = async { getSubtitleTracks(videoUrl) }
        val audioDeferred = async { getAudioTracks(videoUrl) }

        val subtitleTracks = subtitleDeferred.await()
        val audioTracks = audioDeferred.await()

        logcat(LogPriority.INFO, TAG) {
            """
            Tracks detectados para $videoUrl
            - Subtítulos: ${subtitleTracks.size}
            - Audio: ${audioTracks.size}
            """.trimIndent()
        }
        setMediaTracks(subtitleTracks + audioTracks)
    }

    /**
     * Usa FFprobe para obtener la información de las pistas de subtítulos y extrae cada pista a un archivo WebVTT.
     */
    private suspend fun getSubtitleTracks(videoUrl: String): List<MediaTrack> = withContext(Dispatchers.IO) {
        val command = "-timeout 5000000 -hide_banner -v quiet -print_format json " +
            "-show_streams -select_streams s \"$videoUrl\""
        val session = FFprobeKit.execute(command)

        if (!ReturnCode.isSuccess(session.returnCode)) {
            logcat(LogPriority.ERROR, TAG) {
                """
                Error FFprobe subtítulos:
                Código: ${session.returnCode}
                Error: ${session.failStackTrace}
                """.trimIndent()
            }
            return@withContext emptyList<MediaTrack>()
        }

        val streams = parseStreams(session.output, "subtítulos")
        val deferredTracks = streams.mapIndexed { index, stream ->
            async {
                val trackIndex = stream.getInt("index")
                val subtitleUrl = extractSubtitleFile(videoUrl, trackIndex)
                MediaTrack.Builder(index.toLong(), MediaTrack.TYPE_TEXT).apply {
                    setContentId(subtitleUrl)
                    setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    setContentType("text/vtt")
                    setName(stream.optString("tags.title", "Subtítulo ${index + 1}"))
                }.build()
            }
        }
        deferredTracks.awaitAll()
    }

    /**
     * Usa FFprobe para obtener la información de las pistas de audio y extrae cada pista a un archivo AAC en MP4.
     */
    private suspend fun getAudioTracks(videoUrl: String): List<MediaTrack> = withContext(Dispatchers.IO) {
        val command = "-timeout 5000000 -hide_banner -v quiet -print_format json" +
            "-show_streams -select_streams a \"$videoUrl\""
        val session = FFprobeKit.execute(command)

        if (!ReturnCode.isSuccess(session.returnCode)) {
            logcat(LogPriority.ERROR, TAG) {
                """
                Error FFprobe audio:
                Código: ${session.returnCode}
                Error: ${session.failStackTrace}
                """.trimIndent()
            }
            return@withContext emptyList<MediaTrack>()
        }

        val streams = parseStreams(session.output, "audio")
        val deferredTracks = streams.mapIndexed { index, stream ->
            async {
                val trackIndex = stream.getInt("index")
                val audioUrl = extractAudioFile(videoUrl, trackIndex)
                MediaTrack.Builder(index.toLong(), MediaTrack.TYPE_AUDIO).apply {
                    setContentId(audioUrl)
                    setSubtype(MediaTrack.SUBTYPE_NONE)
                    setContentType("audio/mp4")
                    setName(stream.optString("tags.title", "Audio ${index + 1}"))
                }.build()
            }
        }
        deferredTracks.awaitAll()
    }

    // Parsea el JSON obtenido por FFprobe
    private fun parseStreams(jsonOutput: String?, type: String): List<JSONObject> {
        if (jsonOutput.isNullOrEmpty()) {
            logcat(LogPriority.WARN, TAG) { "Respuesta JSON vacía para $type" }
            return emptyList()
        }
        return try {
            val json = JSONObject(jsonOutput)
            if (!json.has("streams")) {
                logcat(LogPriority.WARN, TAG) { "JSON no contiene 'streams' para $type" }
                return emptyList()
            }
            json.getJSONArray("streams").let { streams ->
                (0 until streams.length()).map { streams.getJSONObject(it) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, TAG) {
                "Error parseando $type: ${e.message}\nJSON: ${jsonOutput.take(200)}"
            }
            emptyList()
        }
    }

    /**
     * Extrae la pista de subtítulos a un archivo WebVTT (solo si no existe ya).
     * Se loguea el tamaño del archivo para verificar su integridad.
     */
    private suspend fun extractSubtitleFile(videoUrl: String, trackIndex: Int): String = withContext(Dispatchers.IO) {
        val outputFile = File(activity.cacheDir, "extracted_sub_$trackIndex.vtt")
        if (!outputFile.exists()) {
            val command = "-i \"$videoUrl\" -map 0:s:$trackIndex -c:s webvtt -f webvtt \"${outputFile.absolutePath}\""
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                logcat(LogPriority.ERROR, TAG) {
                    "Error extrayendo subtítulo track $trackIndex: ${session.failStackTrace}"
                }
            } else {
                logcat(LogPriority.INFO, TAG) { "Subtítulo track $trackIndex extraído: ${outputFile.length()} bytes" }
            }
        } else {
            logcat(LogPriority.INFO, TAG) { "Subtítulo track $trackIndex ya existe: ${outputFile.length()} bytes" }
        }
        getLocalFileUrl(outputFile)
    }

    /**
     * Extrae la pista de audio a un archivo AAC en MP4 (solo si no existe ya).
     * Se loguea el tamaño del archivo para verificar su integridad.
     */
    private suspend fun extractAudioFile(videoUrl: String, trackIndex: Int): String = withContext(Dispatchers.IO) {
        val outputFile = File(activity.cacheDir, "extracted_audio_$trackIndex.m4a")
        if (!outputFile.exists()) {
            val command = "-i \"$videoUrl\" -map 0:a:$trackIndex -c:a aac -b:a 192k " +
                "-f mp4 \"${outputFile.absolutePath}\""
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                logcat(LogPriority.ERROR, TAG) { "Error extrayendo audio track $trackIndex: ${session.failStackTrace}" }
            } else {
                logcat(LogPriority.INFO, TAG) { "Audio track $trackIndex extraído: ${outputFile.length()} bytes" }
            }
        } else {
            logcat(LogPriority.INFO, TAG) { "Audio track $trackIndex ya existe: ${outputFile.length()} bytes" }
        }
        getLocalFileUrl(outputFile)
    }

    // Convierte un archivo local en una URL accesible mediante el servidor HTTP local.
    private fun getLocalFileUrl(file: File): String {
        val ip = getLocalIpAddress()
        return "http://$ip:$port/${file.name}"
    }

    private fun getLocalServerUrl(contentUri: String): String {
        activity.startService(Intent(activity, LocalHttpServerService::class.java))
        val ip = getLocalIpAddress()
        val encodedUri = URLEncoder.encode(contentUri, "UTF-8")
        return "http://$ip:$port/file?uri=$encodedUri"
    }

    private fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .first { !it.isLoopbackAddress }
                .hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
