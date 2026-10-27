package com.victor.stream.domain

import com.victor.stream.core.Logger
import com.victor.stream.providers.CandidatePlanner
import com.victor.stream.providers.ProviderRegistry
import com.victor.stream.providers.StreamCache
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ResolveStreamUseCase(
    private val planner: CandidatePlanner,
    private val resolver: StreamResolver,
    private val streamCache: StreamCache
) {
    suspend fun buildPlan(
        media: MediaItemModel,
        episode: EpisodeRef?,
        forceProviderId: String? = null,
        excludedProviderIds: Set<String> = emptySet()
    ): ResolvePlan {
        return planner.buildPlan(
            media = media,
            episode = episode,
            forceProviderId = forceProviderId,
            excludedProviderIds = excludedProviderIds
        )
    }

    fun resolve(plan: ResolvePlan, harvestAll: Boolean = false): Flow<ResolverEvent> {
        return resolver.resolve(plan, harvestAll)
    }

    fun getCachedStream(mediaId: Int, episode: EpisodeRef?, providerId: String): ResolvedStream? {
        return streamCache.get(mediaId, episode?.seasonNumber, episode?.episodeNumber, providerId)
    }
}

class ServerFailoverUseCase(
    private val streamCache: StreamCache,
    private val providerRegistry: ProviderRegistry
) {
    private val tag = "FailoverUseCase"

    operator fun invoke(
        currentSession: PlaybackSession,
        failedProviderId: String
    ): Pair<PlaybackSession?, ServerCandidate?> {
        Logger.w(tag, "Provider failed: $failedProviderId for media=${currentSession.media.title}")
        providerRegistry.recordOutcome(failedProviderId, success = false)

        val seasonNum = currentSession.episode?.seasonNumber
        val episodeNum = currentSession.episode?.episodeNumber
        streamCache.evict(currentSession.media.id, seasonNum, episodeNum, failedProviderId)

        val updatedFailedSet = currentSession.failedProviderIds + failedProviderId

        // 1. Check if another candidate in the plan already has a valid pre-resolved stream in StreamCache
        val cachedBackup = currentSession.plan.candidates
            .filter { it.providerId !in updatedFailedSet }
            .firstNotNullOfOrNull { candidate ->
                streamCache.get(currentSession.media.id, seasonNum, episodeNum, candidate.providerId)
            }

        if (cachedBackup != null && cachedBackup.streamUrl != currentSession.stream.streamUrl) {
            Logger.i(tag, "Switching immediately to cached backup provider: ${cachedBackup.providerId}")
            val newSession = currentSession.copy(
                id = UUID.randomUUID().toString(),
                stream = cachedBackup,
                origin = PlayOrigin.FAILOVER,
                failedProviderIds = updatedFailedSet
            )
            return Pair(newSession, null)
        }

        // 2. Otherwise pick the next untried candidate from the ResolvePlan for live resolution
        val nextCandidate = currentSession.plan.candidates.firstOrNull {
            it.providerId !in updatedFailedSet
        }

        if (nextCandidate != null) {
            Logger.i(tag, "Queueing live failover resolution on candidate: ${nextCandidate.label}")
            val updatedSession = currentSession.copy(
                failedProviderIds = updatedFailedSet,
                origin = PlayOrigin.FAILOVER
            )
            return Pair(updatedSession, nextCandidate)
        }

        Logger.w(tag, "All providers exhausted during failover for ${currentSession.media.title}")
        return Pair(null, null)
    }
}