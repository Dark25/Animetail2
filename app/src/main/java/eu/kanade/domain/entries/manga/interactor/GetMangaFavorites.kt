package eu.kanade.domain.entries.manga.interactor

import eu.kanade.domain.entries.manga.repository.MangaRepository
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.entries.manga.model.Manga

class GetMangaFavorites(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(): List<Manga> {
        return mangaRepository.getMangaFavorites()
    }

    fun subscribe(sourceId: Long): Flow<List<Manga>> {
        return mangaRepository.getMangaFavoritesBySourceId(sourceId)
    }
}