package com.mydigitaldevil.musicplayer.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mydigitaldevil.musicplayer.stats.HourBucket
import com.mydigitaldevil.musicplayer.stats.StatsDao
import com.mydigitaldevil.musicplayer.stats.StatsSummary
import com.mydigitaldevil.musicplayer.stats.TopArtist
import com.mydigitaldevil.musicplayer.stats.TopItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class StatsPeriod { WEEK, MONTH, YEAR, ALL_TIME }

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val summary: StatsSummary = StatsSummary(0, 0, 0, 0, 0),
    val topSongs: List<TopItem> = emptyList(),
    val topArtists: List<TopArtist> = emptyList(),
    /** Plays for each hour 0..23 (missing hours filled with 0). */
    val hours: IntArray = IntArray(24),
) {
    val skipRate: Float get() = if (summary.plays == 0) 0f else summary.skips.toFloat() / summary.plays
    val repeatRatio: Float get() = if (summary.plays == 0) 0f else summary.repeats.toFloat() / summary.plays
    val completionRate: Float get() = if (summary.plays == 0) 0f else summary.completed.toFloat() / summary.plays
    val peakHour: Int? get() = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 }
    val minutes: Long get() = summary.listenedMs / 60_000
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(private val dao: StatsDao) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.MONTH)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    val state: StateFlow<StatsUiState> = _period.flatMapLatest { period ->
        val since = sinceFor(period)
        combine(
            dao.summary(since),
            dao.topSongs(since, 10),
            dao.topArtists(since, 5),
            dao.hourly(since),
        ) { summary, songs, artists, hourly ->
            StatsUiState(period, summary, songs, artists, hourly.toHourArray())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setPeriod(period: StatsPeriod) { _period.value = period }

    private fun List<HourBucket>.toHourArray() = IntArray(24).also { arr -> forEach { if (it.hour in 0..23) arr[it.hour] = it.plays } }

    companion object {
        /** Calendar-aligned windows ("this month", "this year") so Wrapped matches what users expect. */
        fun sinceFor(period: StatsPeriod, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Long {
            val start = when (period) {
                StatsPeriod.WEEK -> today.minusDays(6)
                StatsPeriod.MONTH -> today.with(TemporalAdjusters.firstDayOfMonth())
                StatsPeriod.YEAR -> today.with(TemporalAdjusters.firstDayOfYear())
                StatsPeriod.ALL_TIME -> return 0L
            }
            return start.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}
