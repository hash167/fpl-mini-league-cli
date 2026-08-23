package web

import com.fasterxml.jackson.annotation.JsonProperty
import io.dropwizard.core.Configuration

class FplWebConfiguration(
    @JsonProperty("fplBaseUrl")
    var fplBaseUrl: String = "https://fantasy.premierleague.com/api"
) : Configuration()
