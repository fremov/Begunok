package ru.begunok.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.begunok.workout.IntervalProgram

@Database(
    entities = [
        WorkoutEntity::class, SampleEntity::class, RrIntervalEntity::class, ExerciseSetEntity::class, ColdShowerEntity::class,
        BookEntity::class, ReadingEntryEntity::class, WeightEntryEntity::class,
        MentalResultEntity::class, QuickCheckInEntity::class,
        FinanceTransactionEntity::class, FinanceRuleEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    abstract fun showerDao(): ShowerDao

    abstract fun readingDao(): ReadingDao

    abstract fun weightDao(): WeightDao

    abstract fun mentalDao(): MentalDao

    abstract fun financeDao(): FinanceDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "begunok.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN programName TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN dayType TEXT")
                db.execSQL("ALTER TABLE workouts ADD COLUMN programCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exercise_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`workoutId` INTEGER NOT NULL, `time` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, " +
                        "`exerciseName` TEXT NOT NULL, `setNumber` INTEGER NOT NULL, `reps` INTEGER NOT NULL, " +
                        "`target` INTEGER NOT NULL, `timed` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_sets_workoutId` ON `exercise_sets` (`workoutId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_sets_exerciseId` ON `exercise_sets` (`exerciseId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                IntervalProgram.defaults.forEach { p ->
                    db.execSQL(
                        "UPDATE workouts SET programCompleted = 1 WHERE dayType IS NULL AND programName = ? AND durationMs >= ?",
                        arrayOf<Any>(p.name, p.totalSec * 1000L),
                    )
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cold_showers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `durationSec` INTEGER NOT NULL, `targetSec` INTEGER NOT NULL, " +
                        "`completed` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `author` TEXT, `totalPages` INTEGER NOT NULL, " +
                        "`currentPage` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `finishedAt` INTEGER)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` INTEGER NOT NULL, `time` INTEGER NOT NULL, `fromPage` INTEGER NOT NULL, " +
                        "`toPage` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_entries_bookId` ON `reading_entries` (`bookId`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weight_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`day` INTEGER NOT NULL, `time` INTEGER NOT NULL, `kg` REAL NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_weight_entries_day` ON `weight_entries` (`day`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mental_results` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`test` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, `answers` TEXT NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mental_check_ins` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`completedAt` INTEGER NOT NULL, `mood` INTEGER NOT NULL, `tension` INTEGER NOT NULL, " +
                        "`energy` INTEGER NOT NULL, `context` TEXT)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `finance_transactions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `occurredAt` TEXT NOT NULL, " +
                        "`amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `account` TEXT NOT NULL, " +
                        "`card` TEXT NOT NULL, `category` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, `status` TEXT NOT NULL, `bankIncluded` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `importKey` TEXT, `inclusion` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_transactions_importKey` " +
                        "ON `finance_transactions` (`importKey`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `finance_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `action` TEXT NOT NULL, `direction` TEXT NOT NULL, " +
                        "`account` TEXT, `category` TEXT, `descriptionContains` TEXT NOT NULL, " +
                        "`minAmountMinor` INTEGER, `maxAmountMinor` INTEGER, `currency` TEXT)"
                )
            }
        }
    }
}
