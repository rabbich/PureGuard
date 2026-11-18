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
    version = 3,
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
                    .fallbackToDestructiveMigration()
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
                    if (instance.blockedKeywordsDao().count() < 30) {
                        seedDefaultKeywords(instance)
                    }
                }
                instance
            }
        }

        private suspend fun seedDefaultKeywords(db: PureGuardDatabase) {
            val defaults = listOf(
                // Explicit Adult Domains
                BlockedKeywordEntity(keyword = "pornhub.com", isDomain = true),
                BlockedKeywordEntity(keyword = "xvideos.com", isDomain = true),
                BlockedKeywordEntity(keyword = "xnxx.com", isDomain = true),
                BlockedKeywordEntity(keyword = "onlyfans.com", isDomain = true),
                BlockedKeywordEntity(keyword = "redtube.com", isDomain = true),
                BlockedKeywordEntity(keyword = "youporn.com", isDomain = true),
                BlockedKeywordEntity(keyword = "xhamster.com", isDomain = true),
                BlockedKeywordEntity(keyword = "chaturbate.com", isDomain = true),
                BlockedKeywordEntity(keyword = "stripchat.com", isDomain = true),
                BlockedKeywordEntity(keyword = "brazzers.com", isDomain = true),

                // English Keywords (Explicit + Kissing + Bikini/Thong + YouTube Shorts/X.com triggers)
                BlockedKeywordEntity(keyword = "porn", isDomain = false),
                BlockedKeywordEntity(keyword = "xxx", isDomain = false),
                BlockedKeywordEntity(keyword = "nsfw", isDomain = false),
                BlockedKeywordEntity(keyword = "hentai", isDomain = false),
                BlockedKeywordEntity(keyword = "nude", isDomain = false),
                BlockedKeywordEntity(keyword = "nudegirls", isDomain = false),
                BlockedKeywordEntity(keyword = "naked", isDomain = false),
                BlockedKeywordEntity(keyword = "thong", isDomain = false),
                BlockedKeywordEntity(keyword = "g-string", isDomain = false),
                BlockedKeywordEntity(keyword = "bikini", isDomain = false),
                BlockedKeywordEntity(keyword = "lingerie", isDomain = false),
                BlockedKeywordEntity(keyword = "underboob", isDomain = false),
                BlockedKeywordEntity(keyword = "cleavage", isDomain = false),
                BlockedKeywordEntity(keyword = "tongue kiss", isDomain = false),
                BlockedKeywordEntity(keyword = "tonguekiss", isDomain = false),
                BlockedKeywordEntity(keyword = "french kiss", isDomain = false),
                BlockedKeywordEntity(keyword = "girls kissing", isDomain = false),
                BlockedKeywordEntity(keyword = "women kiss", isDomain = false),
                BlockedKeywordEntity(keyword = "lesbian", isDomain = false),
                BlockedKeywordEntity(keyword = "making out", isDomain = false),
                BlockedKeywordEntity(keyword = "make out", isDomain = false),
                BlockedKeywordEntity(keyword = "only femz", isDomain = false),
                BlockedKeywordEntity(keyword = "onlyfemz", isDomain = false),
                BlockedKeywordEntity(keyword = "twerk", isDomain = false),
                BlockedKeywordEntity(keyword = "boobs", isDomain = false),
                BlockedKeywordEntity(keyword = "booty", isDomain = false),
                BlockedKeywordEntity(keyword = "erotic", isDomain = false),
                BlockedKeywordEntity(keyword = "sensual", isDomain = false),
                BlockedKeywordEntity(keyword = "stripper", isDomain = false),
                BlockedKeywordEntity(keyword = "camgirl", isDomain = false),

                // Hebrew Keywords (Explicit + Kissing + Bikini/Thong + Immodest)
                BlockedKeywordEntity(keyword = "פורנו", isDomain = false),
                BlockedKeywordEntity(keyword = "סרטים כחולים", isDomain = false),
                BlockedKeywordEntity(keyword = "עירום", isDomain = false),
                BlockedKeywordEntity(keyword = "עירומות", isDomain = false),
                BlockedKeywordEntity(keyword = "תוכן למבוגרים", isDomain = false),
                BlockedKeywordEntity(keyword = "חוטיני", isDomain = false),
                BlockedKeywordEntity(keyword = "בחוטיני", isDomain = false),
                BlockedKeywordEntity(keyword = "ביקיני", isDomain = false),
                BlockedKeywordEntity(keyword = "בביקיני", isDomain = false),
                BlockedKeywordEntity(keyword = "הלבשה תחתונה", isDomain = false),
                BlockedKeywordEntity(keyword = "נשיקה צרפתית", isDomain = false),
                BlockedKeywordEntity(keyword = "מתנשקות", isDomain = false),
                BlockedKeywordEntity(keyword = "מתנשקים", isDomain = false),
                BlockedKeywordEntity(keyword = "נשים מתנשקות", isDomain = false),
                BlockedKeywordEntity(keyword = "בנות מתנשקות", isDomain = false),
                BlockedKeywordEntity(keyword = "נשיקות בפה", isDomain = false),
                BlockedKeywordEntity(keyword = "לסביות", isDomain = false),
                BlockedKeywordEntity(keyword = "אירוטי", isDomain = false),
                BlockedKeywordEntity(keyword = "סקסיות", isDomain = false),
                BlockedKeywordEntity(keyword = "זנות", isDomain = false),
                BlockedKeywordEntity(keyword = "חשפנות", isDomain = false)
            )
            db.blockedKeywordsDao().insertAll(defaults)
        }
    }
}
