package web

import FplService
import com.codahale.metrics.health.HealthCheck

class FplHealthCheck(private val service: FplService) : HealthCheck() {
    override fun check(): Result {
        val health = service.health()
        return if (health.fpl == "ok") {
            Result.healthy("FPL bootstrap ok, gameweek=${health.gameweek}")
        } else {
            // App is up even if the public FPL API is flaky.
            Result.healthy("app ok; FPL ${health.fpl}: ${health.error}")
        }
    }
}
