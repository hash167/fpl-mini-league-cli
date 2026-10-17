package web

import CachingFplClient
import FplApi
import FplService
import HttpEspnSource
import MatchStreamHub
import ScoreboardStreamHub
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.dropwizard.core.Application
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.core.setup.Environment
import telemetry.FplTelemetry

class FplWebApplication : Application<FplWebConfiguration>() {
    override fun getName(): String = "fpl-live-leagues"

    override fun initialize(bootstrap: Bootstrap<FplWebConfiguration>) {
        bootstrap.objectMapper.registerModule(kotlinModule())
    }

    override fun run(configuration: FplWebConfiguration, environment: Environment) {
        FplTelemetry.installProcessHooks()
        val snapshotPath = System.getenv("FPL_OVERALL_SNAPSHOT")
            ?.takeIf { it.isNotBlank() }
            ?: configuration.overallSnapshotPath
        val pricesPath = System.getenv("FPL_PRICES_SNAPSHOT")
            ?.takeIf { it.isNotBlank() }
            ?: configuration.pricesSnapshotPath
        val service = FplService(
            CachingFplClient(FplApi(baseUrl = configuration.fplBaseUrl)),
            overallSnapshotPath = snapshotPath,
            pricesSnapshotPath = pricesPath,
            espn = if (System.getenv("FPL_ESPN_DISABLED") == "1") null else HttpEspnSource()
        )
        environment.healthChecks().register("fpl", FplHealthCheck(service))
        environment.jersey().register(FplApiExceptionMapper())
        environment.jersey().register(HealthResource(service))
        environment.jersey().register(EntriesResource(service))
        environment.jersey().register(LeaguesResource(service))
        environment.jersey().register(FixturesResource(service))
        environment.jersey().register(ScoreboardResource(service))
        val streamMapper = environment.objectMapper
        val hub = MatchStreamHub(
            load = { key -> service.matchDetail(key.fixtureId, key.gameweek, key.entryId) },
            serialize = { detail -> streamMapper.writeValueAsString(detail) }
        )
        val scoreboardHub = ScoreboardStreamHub(
            load = { gw -> service.liveScoreboard(gw) },
            serialize = { sb -> streamMapper.writeValueAsString(sb) }
        )
        environment.lifecycle().manage(object : io.dropwizard.lifecycle.Managed {
            override fun stop() { hub.shutdown(); scoreboardHub.shutdown() }
        })
        environment.jersey().register(ScoreboardStreamResource(scoreboardHub, service))
        environment.jersey().register(org.glassfish.jersey.media.sse.SseFeature::class.java)
        environment.jersey().register(SseHeadersFilter())
        environment.jersey().register(VersionResource())
        environment.jersey().register(MatchStreamResource(hub))
        environment.metrics().register("matchStream.activeMatches", com.codahale.metrics.Gauge { hub.activeMatches() })
        environment.metrics().register("matchStream.subscribers", com.codahale.metrics.Gauge { hub.totalSubscribers() })
        environment.jersey().register(OverallResource(service))
        environment.jersey().register(LiveBoardResource(service))
        environment.jersey().register(LivePricesResource(service))
        val dataDir = java.nio.file.Paths.get(snapshotPath).toAbsolutePath().parent?.toString() ?: "data"
        environment.jersey().register(GwStatsResource(dataDir, System.getenv("FPL_INGEST_TOKEN")))
        environment.jersey().register(UiResource())
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            FplWebApplication().run(*args)
        }
    }
}
