package com.walkp.cadence.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Query("SELECT * FROM sessions ORDER BY startMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session_samples WHERE sessionId = :sessionId ORDER BY epochSec")
    suspend fun samplesFor(sessionId: Long): List<SampleEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM session_samples WHERE sessionId = :sessionId")
    suspend fun deleteSamplesFor(sessionId: Long)
}
