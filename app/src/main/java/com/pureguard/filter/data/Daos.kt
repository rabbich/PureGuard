package com.pureguard.filter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppsDao {
    @Query("SELECT * FROM blocked_apps ORDER BY appLabel ASC")
    fun observeAll(): Flow<List<BlockedAppEntity>>

    @Query("SELECT * FROM blocked_apps")
    suspend fun getAll(): List<BlockedAppEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_apps WHERE packageName = :pkg)")
    suspend fun isPackageBlocked(pkg: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: BlockedAppEntity)

    @Query("DELETE FROM blocked_apps WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)
}

@Dao
interface BlockedKeywordsDao {
    @Query("SELECT * FROM blocked_keywords ORDER BY id DESC")
    fun observeAll(): Flow<List<BlockedKeywordEntity>>

    @Query("SELECT * FROM blocked_keywords")
    suspend fun getAll(): List<BlockedKeywordEntity>

    @Query("SELECT COUNT(*) FROM blocked_keywords")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(keyword: BlockedKeywordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(keywords: List<BlockedKeywordEntity>)

    @Query("DELETE FROM blocked_keywords WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface BlockLogsDao {
    @Query("SELECT * FROM block_logs ORDER BY timestamp DESC LIMIT 20")
    fun observeRecentLogs(): Flow<List<BlockLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: BlockLogEntity)

    @Query("DELETE FROM block_logs")
    suspend fun clearAll()
}

@Dao
interface TemporaryWhitelistDao {
    @Query("SELECT EXISTS(SELECT 1 FROM temporary_whitelist WHERE urlOrKey = :key AND expiresAt > :now)")
    suspend fun isWhitelisted(key: String, now: Long = System.currentTimeMillis()): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelist(entry: TemporaryWhitelistEntity)

    @Query("DELETE FROM temporary_whitelist WHERE expiresAt <= :now")
    suspend fun purgeExpired(now: Long = System.currentTimeMillis())
}
