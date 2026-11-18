package com.pureguard.filter.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        BlockedAppEntity::class,
        BlockedKeywordEntity::class,
        BlockLogEntity::class,
        TemporaryWhitelistEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PureGuardDatabase : RoomDatabase() {

    abstract fun blockedAppsDao(): BlockedAppsDao
    abstract fun blockedKeywordsDao(): BlockedKeywordsDao
    abstract fun blockLogsDao(): BlockLogsDao
    abstract fun temporaryWhitelistDao(): TemporaryWhitelistDao

    companion object {
        @Volatile
        private var INSTANCE: PureGuardDatabase? = null

        fun getInstance(context: Context): PureGuardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PureGuardDatabase::class.java,
                    "pureguard_db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                seedDefaultKeywords(getInstance(context))
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                CoroutineScope(Dispatchers.IO).launch {
                    if (instance.blockedKeywordsDao().count() == 0) {
                        seedDefaultKeywords(instance)
                    }
                }
                instance
            }
        }

        private suspend fun seedDefaultKeywords(db: PureGuardDatabase) {
            val defaults = listOf(
                BlockedKeywordEntity(keyword = "pornhub.com", isDomain = true),
                BlockedKeywordEntity(keyword = "xvideos.com", isDomain = true),
                BlockedKeywordEntity(keyword = "xnxx.com", isDomain = true),
                BlockedKeywordEntity(keyword = "onlyfans.com", isDomain = true),
                BlockedKeywordEntity(keyword = "redtube.com", isDomain = true),
                BlockedKeywordEntity(keyword = "porn", isDomain = false),
                BlockedKeywordEntity(keyword = "xxx", isDomain = false),
                BlockedKeywordEntity(keyword = "nsfw", isDomain = false),
                BlockedKeywordEntity(keyword = "hentai", isDomain = false),
                BlockedKeywordEntity(keyword = "פורנו", isDomain = false),
                BlockedKeywordEntity(keyword = "סרטים כחולים", isDomain = false),
                BlockedKeywordEntity(keyword = "עירום", isDomain = false),
                BlockedKeywordEntity(keyword = "תוכן למבוגרים", isDomain = false)
            )
            db.blockedKeywordsDao().insertAll(defaults)
        }
    }
}
