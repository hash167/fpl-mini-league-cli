package web

import com.fasterxml.jackson.annotation.JsonProperty
import io.dropwizard.core.Configuration

class FplWebConfiguration(
    @JsonProperty("fplBaseUrl")
    var fplBaseUrl: String = "https://fantasy.premierleague.com/api",
    @JsonProperty("overallSnapshotPath")
    var overallSnapshotPath: String = "data/live-overall-snapshot.json",
    @JsonProperty("pricesSnapshotPath")
    var pricesSnapshotPath: String = "data/prices-snapshot.json"
) : Configuration()
