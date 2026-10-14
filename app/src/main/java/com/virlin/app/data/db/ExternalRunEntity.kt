package com.virlin.app.data.db

import androidx.room.*

@Entity(tableName = "external_run_intervals")
data class ExternalRunEntity(@PrimaryKey val id: String, val ownerId: String, val taskId: String?, val startedAt: Long, val endedAt: Long)

@Dao
interface ExternalRunDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(run: ExternalRunEntity)
    @Query("SELECT * FROM external_run_intervals") suspend fun all(): List<ExternalRunEntity>
}
