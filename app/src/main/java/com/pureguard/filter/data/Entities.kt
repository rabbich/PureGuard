package com.pureguard.filter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedAppEntity(
    @PrimaryKey
    val packageName: String,
    val appLabel: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blocked_keywords")
data class BlockedKeywordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyword: String,
    val isDomain: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "block_logs")
data class BlockLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val targetAppOrUrl: String,
    val triggerReason: String,
    val inferenceTimeMs: Long = 0L
)

@Entity(tableName = "temporary_whitelist")
data class TemporaryWhitelistEntity(
    @PrimaryKey
    val urlOrKey: String,
    val expiresAt: Long
)
