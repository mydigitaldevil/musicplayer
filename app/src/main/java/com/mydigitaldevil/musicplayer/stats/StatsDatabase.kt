package com.mydigitaldevil.musicplayer.stats

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One listening session of one item. Written by [PlayEventLogger] inside the playback service. */
@Entity(tableName = "play_events", indices = [Index("startedAt"), Index("mediaId")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val isVideo: Boolean,
    /** Wall-clock millis when playback of this item began. */
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    /** Left before the halfway point without finishing. */
    val skipped: Boolean,
    val completed: Boolean,
    /** Started again by repeat mode right after it finished. */
    val repeated: Boolean,
)

data class TopItem(val mediaId: String, val title: String, val artist: String?, val plays: Int, val listenedMs: Long)
data class TopArtist(val artist: String, val plays: Int, val listenedMs: Long)
data class StatsSummary(val plays: Int, val skips: Int, val repeats: Int, val completed: Int, val listenedMs: Long)
data class HourBucket(val hour: Int, val plays: Int)

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(event: PlayEventEntity)

    @Query(
        """SELECT mediaId, title, artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs
           FROM play_events WHERE startedAt >= :since
           GROUP BY mediaId ORDER BY plays DESC, listenedMs DESC LIMIT :limit""",
    )
    fun topSongs(since: Long, limit: Int): Flow<List<TopItem>>

    @Query(
        """SELECT artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs
           FROM play_events WHERE startedAt >= :since AND artist IS NOT NULL AND isVideo = 0
           GROUP BY artist ORDER BY plays DESC, listenedMs DESC LIMIT :limit""",
    )
    fun topArtists(since: Long, limit: Int): Flow<List<TopArtist>>

    @Query(
        """SELECT COUNT(*) AS plays,
                  COALESCE(SUM(skipped), 0) AS skips,
                  COALESCE(SUM(repeated), 0) AS repeats,
                  COALESCE(SUM(completed), 0) AS completed,
                  COALESCE(SUM(listenedMs), 0) AS listenedMs
           FROM play_events WHERE startedAt >= :since""",
    )
    fun summary(since: Long): Flow<StatsSummary>

    /** Plays per local hour of day (0–23). Hours with no plays are absent. */
    @Query(
        """SELECT CAST(strftime('%H', startedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
                  COUNT(*) AS plays
           FROM play_events WHERE startedAt >= :since
           GROUP BY hour ORDER BY hour""",
    )
    fun hourly(since: Long): Flow<List<HourBucket>>

    @Query("DELETE FROM play_events")
    suspend fun clear()
}

@Database(entities = [PlayEventEntity::class], version = 1, exportSchema = true)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun dao(): StatsDao
}
