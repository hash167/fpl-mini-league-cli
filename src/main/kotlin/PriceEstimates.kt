const val PRICE_ESTIMATE_SLICE = 20

const val PRICE_ESTIMATE_NOTE =
    "Price labels are a simple estimate from this GW's official net transfers, not FPL's algorithm."

fun applyPriceEstimates(players: List<LiveBoardPlayer>): List<LiveBoardPlayer> {
    val riseIds = players.filter { it.netTransfers > 0 }
        .sortedByDescending { it.netTransfers }
        .take(PRICE_ESTIMATE_SLICE)
        .map { it.id }
        .toSet()
    val fallIds = players.filter { it.netTransfers < 0 }
        .sortedBy { it.netTransfers }
        .take(PRICE_ESTIMATE_SLICE)
        .map { it.id }
        .toSet()
    return players.map { p ->
        val official = officialMoveReason(p.costChangeEvent)
        val (label, sliceReason) = when {
            p.id in riseIds -> "likely_rise" to
                "Among the ${PRICE_ESTIMATE_SLICE} highest official net transfers in this GW (estimate)."
            p.id in fallIds -> "likely_fall" to
                "Among the ${PRICE_ESTIMATE_SLICE} highest official net transfers out this GW (estimate)."
            else -> "stable" to
                "Not in the top net-in or net-out slice this GW (estimate)."
        }
        val reason = listOfNotNull(official, sliceReason).joinToString(" ")
        p.copy(priceEstimate = label, priceEstimateReason = reason)
    }
}

fun officialMoveReason(costChangeEvent: Int): String? {
    if (costChangeEvent == 0) return null
    val millions = kotlin.math.abs(costChangeEvent) / 10.0
    val sign = if (costChangeEvent > 0) "+" else "-"
    return "Official GW price already moved $sign£${"%.1f".format(millions)}m."
}


fun estimateLikelyRises(players: Collection<PlayerInfo>): List<PriceRiseEstimate> {
    val rows = players.map { info ->
        val tin = info.transfersInEvent ?: 0
        val tout = info.transfersOutEvent ?: 0
        LiveBoardPlayer(
            id = info.id,
            webName = info.webName,
            team = info.teamShortName,
            position = elementTypeName(info.elementType),
            minutes = 0,
            livePoints = 0,
            nowCost = info.nowCost ?: 0,
            costChangeEvent = info.costChangeEvent ?: 0,
            transfersInEvent = tin,
            transfersOutEvent = tout,
            netTransfers = tin - tout,
            selectedBy = info.selectedBy,
            priceEstimate = "",
            priceEstimateReason = ""
        )
    }
    val labeled = applyPriceEstimates(rows)
    val alreadyRose = rows.filter { it.costChangeEvent > 0 }.map { it.id }.toSet()
    return labeled
        .filter { it.priceEstimate == "likely_rise" || it.id in alreadyRose }
        .map { p ->
            val label = "likely_rise"
            val reason = if (p.priceEstimate == "likely_rise") p.priceEstimateReason
            else listOfNotNull(officialMoveReason(p.costChangeEvent), p.priceEstimateReason).joinToString(" ")
            PriceRiseEstimate(
                id = p.id,
                webName = p.webName,
                team = p.team,
                position = p.position,
                nowCost = p.nowCost,
                nowPounds = tenthsToPoundsString(p.nowCost),
                netTransfers = p.netTransfers,
                transfersInEvent = p.transfersInEvent,
                transfersOutEvent = p.transfersOutEvent,
                selectedBy = p.selectedBy,
                priceEstimate = label,
                priceEstimateReason = reason
            )
        }
        .sortedByDescending { it.netTransfers }
}
