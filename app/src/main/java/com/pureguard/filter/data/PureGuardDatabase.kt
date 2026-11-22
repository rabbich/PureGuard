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
                    seedDefaultKeywords(instance)
                }
                instance
            }
        }

        private suspend fun seedDefaultKeywords(db: PureGuardDatabase) {
            // Remove news/religious/community false-positive terms from existing databases automatically on update
            db.blockedKeywordsDao().deleteByKeyword("זנות")
            db.blockedKeywordsDao().deleteByKeyword("תוכן למבוגרים")

            val existingLower = db.blockedKeywordsDao().getAll().map { it.keyword.lowercase().trim() }.toSet()
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

                // English Keywords (Explicit + Kissing + Bikini/Thong + Lingerie/Sheer + YouTube Shorts/X.com triggers)
                BlockedKeywordEntity(keyword = "porn", isDomain = false),
                BlockedKeywordEntity(keyword = "sex", isDomain = false),
                BlockedKeywordEntity(keyword = "topless", isDomain = false),
                BlockedKeywordEntity(keyword = "orgasm", isDomain = false),
                BlockedKeywordEntity(keyword = "masturbate", isDomain = false),
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
                BlockedKeywordEntity(keyword = "lace-bra", isDomain = false),
                BlockedKeywordEntity(keyword = "lace bra", isDomain = false),
                BlockedKeywordEntity(keyword = "sheer bra", isDomain = false),
                BlockedKeywordEntity(keyword = "transparent bra", isDomain = false),
                BlockedKeywordEntity(keyword = "see-through", isDomain = false),
                BlockedKeywordEntity(keyword = "see through bra", isDomain = false),
                BlockedKeywordEntity(keyword = "panties", isDomain = false),
                BlockedKeywordEntity(keyword = "underboob", isDomain = false),
                BlockedKeywordEntity(keyword = "sideboob", isDomain = false),
                BlockedKeywordEntity(keyword = "cameltoe", isDomain = false),
                BlockedKeywordEntity(keyword = "cleavage", isDomain = false),
                BlockedKeywordEntity(keyword = "striptease", isDomain = false),
                BlockedKeywordEntity(keyword = "lap dance", isDomain = false),
                BlockedKeywordEntity(keyword = "belly dance", isDomain = false),
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

                // Hebrew Keywords (Explicit + Kissing + Bikini/Thong + Lingerie/Immodest)
                BlockedKeywordEntity(keyword = "סקס", isDomain = false),
                BlockedKeywordEntity(keyword = "יחסי מין", isDomain = false),
                BlockedKeywordEntity(keyword = "אורגזמה", isDomain = false),
                BlockedKeywordEntity(keyword = "אוננות", isDomain = false),
                BlockedKeywordEntity(keyword = "חזה חשוף", isDomain = false),
                BlockedKeywordEntity(keyword = "בלי חזיה", isDomain = false),
                BlockedKeywordEntity(keyword = "בלי חזייה", isDomain = false),
                BlockedKeywordEntity(keyword = "ללא חזיה", isDomain = false),
                BlockedKeywordEntity(keyword = "ללא חזייה", isDomain = false),
                BlockedKeywordEntity(keyword = "טופלס", isDomain = false),
                BlockedKeywordEntity(keyword = "פורנו", isDomain = false),
                BlockedKeywordEntity(keyword = "סרטים כחולים", isDomain = false),
                BlockedKeywordEntity(keyword = "עירום", isDomain = false),
                BlockedKeywordEntity(keyword = "עירומות", isDomain = false),
                BlockedKeywordEntity(keyword = "חוטיני", isDomain = false),
                BlockedKeywordEntity(keyword = "בחוטיני", isDomain = false),
                BlockedKeywordEntity(keyword = "ביקיני", isDomain = false),
                BlockedKeywordEntity(keyword = "בביקיני", isDomain = false),
                BlockedKeywordEntity(keyword = "הלבשה תחתונה", isDomain = false),
                BlockedKeywordEntity(keyword = "חזיה שקופה", isDomain = false),
                BlockedKeywordEntity(keyword = "חזייה שקופה", isDomain = false),
                BlockedKeywordEntity(keyword = "בחזיה", isDomain = false),
                BlockedKeywordEntity(keyword = "בתחתונים", isDomain = false),
                BlockedKeywordEntity(keyword = "בגד גוף חשוף", isDomain = false),
                BlockedKeywordEntity(keyword = "ריקוד בטן", isDomain = false),
                BlockedKeywordEntity(keyword = "נשיקה צרפתית", isDomain = false),
                BlockedKeywordEntity(keyword = "מתנשקות", isDomain = false),
                BlockedKeywordEntity(keyword = "מתנשקים", isDomain = false),
                BlockedKeywordEntity(keyword = "נשים מתנשקות", isDomain = false),
                BlockedKeywordEntity(keyword = "בנות מתנשקות", isDomain = false),
                BlockedKeywordEntity(keyword = "נשיקות בפה", isDomain = false),
                BlockedKeywordEntity(keyword = "לסביות", isDomain = false),
                BlockedKeywordEntity(keyword = "אירוטי", isDomain = false),
                BlockedKeywordEntity(keyword = "סקסיות", isDomain = false),
                BlockedKeywordEntity(keyword = "חשפנות", isDomain = false)
            )
            val missing = defaults.filter { !existingLower.contains(it.keyword.lowercase().trim()) }
            if (missing.isNotEmpty()) {
                db.blockedKeywordsDao().insertAll(missing)
            }
        }
    }
}
