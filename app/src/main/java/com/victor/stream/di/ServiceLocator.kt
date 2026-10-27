package com.victor.stream.di

import android.content.Context
import com.victor.stream.data.CatalogRepository
import com.victor.stream.data.PrefsStore
import com.victor.stream.data.SessionStore
import com.victor.stream.domain.ResolveStreamUseCase
import com.victor.stream.domain.ServerFailoverUseCase
import com.victor.stream.domain.StreamResolver
import com.victor.stream.providers.CandidatePlanner
import com.victor.stream.providers.IdBridge
import com.victor.stream.providers.ProviderRegistry
import com.victor.stream.providers.StreamCache
import com.victor.stream.resolver.WebViewStreamResolver

object ServiceLocator {
    @Volatile
    private var initialized = false

    lateinit var prefsStore: PrefsStore
        private set

    lateinit var sessionStore: SessionStore
        private set

    lateinit var providerRegistry: ProviderRegistry
        private set

    lateinit var streamCache: StreamCache
        private set

    lateinit var idBridge: IdBridge
        private set

    lateinit var catalogRepository: CatalogRepository
        private set

    lateinit var candidatePlanner: CandidatePlanner
        private set

    lateinit var streamResolver: StreamResolver
        private set

    lateinit var resolveStreamUseCase: ResolveStreamUseCase
        private set

    lateinit var serverFailoverUseCase: ServerFailoverUseCase
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext

            prefsStore = PrefsStore(appContext)
            sessionStore = SessionStore()
            providerRegistry = ProviderRegistry()
            streamCache = StreamCache()

            var tempCatalogRepo: CatalogRepository? = null
            idBridge = IdBridge(
                remoteResolver = { media ->
                    tempCatalogRepo?.resolveRemoteAnimeIds(media)
                }
            )

            catalogRepository = CatalogRepository(prefsStore, idBridge).also {
                tempCatalogRepo = it
            }

            candidatePlanner = CandidatePlanner(providerRegistry, idBridge)

            streamResolver = WebViewStreamResolver(
                context = appContext,
                providerRegistry = providerRegistry,
                streamCache = streamCache,
                catalogRepository = catalogRepository
            )

            resolveStreamUseCase = ResolveStreamUseCase(
                planner = candidatePlanner,
                resolver = streamResolver,
                streamCache = streamCache
            )

            serverFailoverUseCase = ServerFailoverUseCase(
                streamCache = streamCache,
                providerRegistry = providerRegistry
            )

            initialized = true
        }
    }
}