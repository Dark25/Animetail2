package tachiyomi.domain.entries.manga.interactor

import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository

class NetworkToLocalManga(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(manga: Manga): Manga {
        val localManga = getManga(manga.url, manga.source)
        return when {
            localManga == null -> {
                val id = insertManga(manga)
                manga.copy(id = id!!)
            }
            !localManga.favorite -> {
                // if the manga isn't a favorite, set its display title from source
                // if it later becomes a favorite, updated title will go to db
                localManga.copy(ogTitle = manga.title)
            }
            else -> {
                localManga
            }
        }
    }

    private suspend fun getManga(url: String, sourceId: Long): Manga? {
        return mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
    }

    private suspend fun insertManga(manga: Manga): Long? {
        return mangaRepository.insertManga(manga)
    }
}
