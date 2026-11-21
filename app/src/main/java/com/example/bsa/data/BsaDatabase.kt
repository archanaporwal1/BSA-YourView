package com.example.bsa.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_cases")
data class SavedCaseEntity(
    @PrimaryKey val reportId: String,
    val applicantId: String,
    val holderName: String,
    val bankName: String,
    val accountNumber: String,
    val statementPeriod: String,
    val productType: String,
    val facilityType: String,
    val forensicStatus: String,
    val flaggedCount: Int,
    val transactionCount: Int,
    val generatedAtMillis: Long,
    val reportJson: String
)

data class SavedCaseSummary(
    val reportId: String,
    val applicantId: String,
    val holderName: String,
    val bankName: String,
    val accountNumber: String,
    val statementPeriod: String,
    val productType: String,
    val facilityType: String,
    val forensicStatus: String,
    val flaggedCount: Int,
    val transactionCount: Int,
    val generatedAtMillis: Long
)

@Dao
interface SavedCaseDao {
    @Query(
        """
        SELECT reportId, applicantId, holderName, bankName, accountNumber,
               statementPeriod, productType, facilityType, forensicStatus,
               flaggedCount, transactionCount, generatedAtMillis
        FROM saved_cases
        ORDER BY generatedAtMillis DESC
        """
    )
    fun observeAllSummaries(): Flow<List<SavedCaseSummary>>

    @Query("SELECT * FROM saved_cases WHERE reportId = :reportId LIMIT 1")
    suspend fun getCaseById(reportId: String): SavedCaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCase(entity: SavedCaseEntity)

    @Query("DELETE FROM saved_cases WHERE reportId = :reportId")
    suspend fun deleteById(reportId: String)

    @Query("DELETE FROM saved_cases")
    suspend fun deleteAll()
}

@Database(entities = [SavedCaseEntity::class], version = 1, exportSchema = false)
abstract class BsaDatabase : RoomDatabase() {
    abstract fun savedCaseDao(): SavedCaseDao

    companion object {
        @Volatile
        private var INSTANCE: BsaDatabase? = null

        fun getInstance(context: Context): BsaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BsaDatabase::class.java,
                    "bank_analysier_yv_offline.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
