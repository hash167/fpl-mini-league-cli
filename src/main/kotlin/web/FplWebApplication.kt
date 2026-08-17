package web

import CachingFplClient
import FplApi
import FplService
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.dropwizard.core.Application
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.core.setup.Environment

class FplWebApplication : Application<FplWebConfiguration>() {
    override fun getName(): String = "fpl-live-leagues"

    override fun initialize(bootstrap: Bootstrap<FplWebConfiguration>) {
        bootstrap.objectMapper.registerModule(kotlinModule())
    }

    override fun run(configuration: FplWebConfiguration, environment: Environment) {
        val service = FplService(CachingFplClient(FplApi(baseUrl = configuration.fplBaseUrl)))
        environment.healthChecks().register("fpl", FplHealthCheck(service))
        environment.jersey().register(FplApiExceptionMapper())
        environment.jersey().register(HealthResource(service))
        environment.jersey().register(EntriesResource(service))
        environment.jersey().register(LeaguesResource(service))
        environment.jersey().register(UiResource())
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            FplWebApplication().run(*args)
        }
    }
}
