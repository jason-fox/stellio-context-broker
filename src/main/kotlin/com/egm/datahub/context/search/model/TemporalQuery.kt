package com.egm.datahub.context.search.model

import java.time.OffsetDateTime

data class TemporalQuery(
    val timerel: Timerel,
    val time: OffsetDateTime,
    val endTime: OffsetDateTime?
) {
    enum class Timerel {
        BEFORE,
        AFTER,
        BETWEEN
    }
}
