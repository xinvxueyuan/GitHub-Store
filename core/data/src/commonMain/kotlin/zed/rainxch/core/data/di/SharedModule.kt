package zed.rainxch.core.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.dsl.module
import zed.rainxch.core.data.cache.CacheManager
import zed.rainxch.core.data.data_source.TokenStore
import zed.rainxch.core.data.services.DefaultDownloadOrchestrator
import zed.rainxch.core.data.data_source.impl.DefaultTokenStore
import zed.rainxch.core.data.local.db.AppDatabase
import zed.rainxch.core.data.local.db.dao.CacheDao
import zed.rainxch.core.data.local.db.dao.FavoriteRepoDao
import zed.rainxch.core.data.local.db.dao.InstalledAppDao
import zed.rainxch.core.data.local.db.dao.SearchHistoryDao
import zed.rainxch.core.data.local.db.dao.SeenRepoDao
import zed.rainxch.core.data.local.db.dao.StarredRepoDao
import zed.rainxch.core.data.local.db.dao.UpdateHistoryDao
import zed.rainxch.core.data.logging.KermitLogger
import zed.rainxch.core.data.network.BackendApiClient
import zed.rainxch.core.data.network.GitHubClientProvider
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.data.network.ProxyTesterImpl
import zed.rainxch.core.data.network.TranslationClientProvider
import zed.rainxch.core.data.repository.AuthenticationStateImpl
import zed.rainxch.core.data.repository.FavouritesRepositoryImpl
import zed.rainxch.core.data.repository.InstalledAppsRepositoryImpl
import zed.rainxch.core.data.repository.ProxyRepositoryImpl
import zed.rainxch.core.data.repository.DeviceIdentityRepositoryImpl
import zed.rainxch.core.data.repository.RateLimitRepositoryImpl
import zed.rainxch.core.data.repository.SearchHistoryRepositoryImpl
import zed.rainxch.core.data.repository.TelemetryRepositoryImpl
import zed.rainxch.core.data.repository.SeenReposRepositoryImpl
import zed.rainxch.core.data.repository.StarredRepositoryImpl
import zed.rainxch.core.data.repository.TweaksRepositoryImpl
import zed.rainxch.core.domain.getPlatform
import zed.rainxch.core.domain.logging.GitHubStoreLogger
import zed.rainxch.core.domain.model.Platform
import zed.rainxch.core.domain.model.ProxyConfig
import zed.rainxch.core.domain.model.ProxyScope
import zed.rainxch.core.domain.network.ProxyTester
import zed.rainxch.core.domain.system.DownloadOrchestrator
import zed.rainxch.core.domain.repository.AuthenticationState
import zed.rainxch.core.domain.repository.DeviceIdentityRepository
import zed.rainxch.core.domain.repository.FavouritesRepository
import zed.rainxch.core.domain.repository.InstalledAppsRepository
import zed.rainxch.core.domain.repository.ProxyRepository
import zed.rainxch.core.domain.repository.RateLimitRepository
import zed.rainxch.core.domain.repository.SearchHistoryRepository
import zed.rainxch.core.domain.repository.SeenReposRepository
import zed.rainxch.core.domain.repository.StarredRepository
import zed.rainxch.core.domain.repository.TelemetryRepository
import zed.rainxch.core.domain.repository.TweaksRepository
import zed.rainxch.core.domain.use_cases.SyncInstalledAppsUseCase

val coreModule =
    module {
        single {
            CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        single<GitHubStoreLogger> {
            KermitLogger
        }

        single<Platform> {
            getPlatform()
        }

        single<AuthenticationState> {
            AuthenticationStateImpl(
                tokenStore = get(),
            )
        }

        single<FavouritesRepository> {
            FavouritesRepositoryImpl(
                favoriteRepoDao = get(),
                installedAppsDao = get(),
            )
        }

        single<InstalledAppsRepository> {
            InstalledAppsRepositoryImpl(
                database = get(),
                installedAppsDao = get(),
                historyDao = get(),
                installer = get(),
                clientProvider = get(),
            )
        }

        single<StarredRepository> {
            StarredRepositoryImpl(
                installedAppsDao = get(),
                starredRepoDao = get(),
                platform = get(),
                clientProvider = get(),
            )
        }

        single<TweaksRepository> {
            TweaksRepositoryImpl(
                preferences = get(),
            )
        }

        single<SeenReposRepository> {
            SeenReposRepositoryImpl(
                seenRepoDao = get(),
            )
        }

        single<SearchHistoryRepository> {
            SearchHistoryRepositoryImpl(
                searchHistoryDao = get(),
            )
        }

        single<ProxyRepository> {
            ProxyRepositoryImpl(
                preferences = get(),
            )
        }

        single<ProxyTester> {
            ProxyTesterImpl()
        }

        single<SyncInstalledAppsUseCase> {
            SyncInstalledAppsUseCase(
                packageMonitor = get(),
                installedAppsRepository = get(),
                platform = get(),
                logger = get(),
            )
        }

        single<CacheManager> {
            CacheManager(cacheDao = get())
        }

        single<BackendApiClient> {
            BackendApiClient(
                proxyConfigFlow = ProxyManager.configFlow(ProxyScope.DISCOVERY),
            )
        }

        single<DeviceIdentityRepository> {
            DeviceIdentityRepositoryImpl(
                preferences = get(),
            )
        }

        single<TelemetryRepository> {
            TelemetryRepositoryImpl(
                backendApiClient = get(),
                deviceIdentity = get(),
                tweaksRepository = get(),
                platform = get(),
                appScope = get(),
                logger = get(),
            )
        }

        // Application-scoped download / install orchestrator. Lives
        // for the process lifetime so downloads survive screen
        // navigation. ViewModels are observers, never owners.
        single<DownloadOrchestrator> {
            DefaultDownloadOrchestrator(
                downloader = get(),
                installer = get(),
                installedAppsRepository = get(),
                pendingInstallNotifier = get(),
                appScope = get(),
            )
        }
    }

val networkModule =
    module {
        // Seed ProxyManager from persisted per-scope configs *before* any
        // HTTP client is constructed. Blocks briefly (≤1.5s per scope) on
        // DataStore reads so the very first request uses the user's saved
        // proxy rather than the System default. Failures are swallowed and
        // fall back to System — we'd rather network work than the app stall
        // on startup if DataStore is slow.
        single<GitHubClientProvider>(createdAtStart = true) {
            val repository = get<ProxyRepository>()
            ProxyScope.entries.forEach { scope ->
                val saved =
                    runBlocking {
                        runCatching {
                            withTimeout(1_500L) {
                                repository.getProxyConfig(scope).first()
                            }
                        }.getOrDefault(ProxyConfig.System)
                    }
                ProxyManager.setConfig(scope, saved)
            }

            GitHubClientProvider(
                tokenStore = get(),
                rateLimitRepository = get(),
                authenticationState = get(),
                proxyConfigFlow = ProxyManager.configFlow(ProxyScope.DISCOVERY),
            )
        }

        single<TranslationClientProvider>(createdAtStart = true) {
            TranslationClientProvider(
                proxyConfigFlow = ProxyManager.configFlow(ProxyScope.TRANSLATION),
            )
        }

        single<TokenStore> {
            DefaultTokenStore(
                dataStore = get(),
            )
        }

        single<RateLimitRepository> {
            RateLimitRepositoryImpl()
        }
    }

val databaseModule =
    module {
        single<FavoriteRepoDao> {
            get<AppDatabase>().favoriteRepoDao
        }

        single<InstalledAppDao> {
            get<AppDatabase>().installedAppDao
        }

        single<StarredRepoDao> {
            get<AppDatabase>().starredReposDao
        }

        single<UpdateHistoryDao> {
            get<AppDatabase>().updateHistoryDao
        }

        single<CacheDao> {
            get<AppDatabase>().cacheDao
        }

        single<SeenRepoDao> {
            get<AppDatabase>().seenRepoDao
        }

        single<SearchHistoryDao> {
            get<AppDatabase>().searchHistoryDao
        }
    }
