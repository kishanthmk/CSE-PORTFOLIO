package lk.cseportfolio.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.math.BigDecimal
import java.security.KeyStore
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import lk.cseportfolio.core.CashType
import lk.cseportfolio.core.InterestMethod
import lk.cseportfolio.core.MarginEntryType
import lk.cseportfolio.core.PayMethod
import lk.cseportfolio.core.TxnType
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// ---------------------------------------------------------------- entities

@Entity(tableName = "companies")
data class CompanyEntity(
    @PrimaryKey val symbol: String,
    val name: String = "",
    val last: BigDecimal? = null,
    val prevClose: BigDecimal? = null,
    val change: BigDecimal? = null,
    val changePct: BigDecimal? = null,
    val volume: Long? = null,
    val high52: BigDecimal? = null,
    val low52: BigDecimal? = null,
    val priceUpdatedAt: Long = 0,
    val priceSource: String = ""
)

@Entity(tableName = "transactions", indices = [Index("symbol"), Index("date")])
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val symbol: String,
    val type: TxnType,
    val quantity: Long = 0,
    val price: BigDecimal = BigDecimal.ZERO,
    val brokerage: BigDecimal = BigDecimal.ZERO,
    val cseFee: BigDecimal = BigDecimal.ZERO,
    val secFee: BigDecimal = BigDecimal.ZERO,
    val cdsFee: BigDecimal = BigDecimal.ZERO,
    val tax: BigDecimal = BigDecimal.ZERO,
    val other: BigDecimal = BigDecimal.ZERO,
    val payment: PayMethod = PayMethod.CASH,
    val marginAccountId: Long? = null,
    val financedAmount: BigDecimal = BigDecimal.ZERO,
    val loanRepayment: BigDecimal = BigDecimal.ZERO,
    val splitNum: Int = 1,
    val splitDen: Int = 1,
    val note: String = "",
    val feeScheduleId: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false
)

@Entity(tableName = "cash_entries", indices = [Index("date")])
data class CashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val type: CashType,
    val amount: BigDecimal,
    val symbol: String? = null,
    val note: String = "",
    val updatedAt: Long = 0,
    val deleted: Boolean = false
)

@Entity(tableName = "margin_accounts")
data class MarginAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val broker: String,
    val accountNo: String = "",
    val facilityLimit: BigDecimal = BigDecimal.ZERO,
    val interestRate: BigDecimal = BigDecimal.ZERO,
    val method: InterestMethod = InterestMethod.SIMPLE_DAILY_365,
    val startDate: LocalDate? = null,
    val repaymentDate: LocalDate? = null,
    val warnUtilizationPct: BigDecimal = BigDecimal("80"),
    val marginCallLtvPct: BigDecimal = BigDecimal("50"),
    val wholePortfolioCollateral: Boolean = false,
    val note: String = ""
)

@Entity(tableName = "margin_entries", indices = [Index("accountId"), Index("date")])
data class MarginEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val date: LocalDate,
    val type: MarginEntryType,
    val amount: BigDecimal = BigDecimal.ZERO,
    val rate: BigDecimal = BigDecimal.ZERO,
    val note: String = "",
    val updatedAt: Long = 0,
    val deleted: Boolean = false
)

@Entity(tableName = "fee_schedules")
data class FeeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val effectiveFrom: LocalDate,
    val brokeragePct: BigDecimal = BigDecimal.ZERO,
    val csePct: BigDecimal = BigDecimal.ZERO,
    val secPct: BigDecimal = BigDecimal.ZERO,
    val cdsPct: BigDecimal = BigDecimal.ZERO,
    val taxPct: BigDecimal = BigDecimal.ZERO,
    val otherPct: BigDecimal = BigDecimal.ZERO,
    val minBrokerage: BigDecimal = BigDecimal.ZERO,
    val fixedBrokerage: BigDecimal = BigDecimal.ZERO,
    val fixedOther: BigDecimal = BigDecimal.ZERO,
    val tier2Threshold: BigDecimal? = null,
    val t2BrokeragePct: BigDecimal = BigDecimal.ZERO,
    val t2CsePct: BigDecimal = BigDecimal.ZERO,
    val t2SecPct: BigDecimal = BigDecimal.ZERO,
    val t2CdsPct: BigDecimal = BigDecimal.ZERO
)

@Entity(tableName = "watchlist")
data class WatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val name: String = "",
    val targetBuy: BigDecimal? = null,
    val targetSell: BigDecimal? = null,
    val notes: String = "",
    val buyNotifiedAt: Long = 0,
    val sellNotifiedAt: Long = 0
)

@Entity(tableName = "audit_log", indices = [Index("timestamp")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val entity: String,
    val entityId: Long,
    val action: String,
    val summary: String,
    val before: String = "",
    val after: String = ""
)

// ---------------------------------------------------------------- converters

class Converters {
    @TypeConverter fun decToString(v: BigDecimal?): String? = v?.toPlainString()
    @TypeConverter fun stringToDec(v: String?): BigDecimal? = v?.let { BigDecimal(it) }
    @TypeConverter fun dateToString(v: LocalDate?): String? = v?.toString()
    @TypeConverter fun stringToDate(v: String?): LocalDate? = v?.let { LocalDate.parse(it) }
}

// ---------------------------------------------------------------- dao

@Dao
interface AppDao {
    // transactions
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY date, id") fun txns(): Flow<List<TxnEntity>>
    @Query("SELECT * FROM transactions WHERE deleted = 1 ORDER BY updatedAt DESC") fun deletedTxns(): Flow<List<TxnEntity>>
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun txn(id: Long): TxnEntity?
    @Query("SELECT * FROM transactions") suspend fun allTxns(): List<TxnEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTxn(e: TxnEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTxns(e: List<TxnEntity>)
    @Query("DELETE FROM transactions WHERE id = :id") suspend fun purgeTxn(id: Long)
    @Query("DELETE FROM transactions") suspend fun clearTxns()

    // cash
    @Query("SELECT * FROM cash_entries WHERE deleted = 0 ORDER BY date, id") fun cash(): Flow<List<CashEntity>>
    @Query("SELECT * FROM cash_entries WHERE deleted = 1 ORDER BY updatedAt DESC") fun deletedCash(): Flow<List<CashEntity>>
    @Query("SELECT * FROM cash_entries WHERE id = :id") suspend fun cashEntry(id: Long): CashEntity?
    @Query("SELECT * FROM cash_entries") suspend fun allCash(): List<CashEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCash(e: CashEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCashList(e: List<CashEntity>)
    @Query("DELETE FROM cash_entries WHERE id = :id") suspend fun purgeCash(id: Long)
    @Query("DELETE FROM cash_entries") suspend fun clearCash()

    // margin
    @Query("SELECT * FROM margin_accounts ORDER BY id") fun accounts(): Flow<List<MarginAccountEntity>>
    @Query("SELECT * FROM margin_accounts") suspend fun allAccounts(): List<MarginAccountEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(e: MarginAccountEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccounts(e: List<MarginAccountEntity>)
    @Query("DELETE FROM margin_accounts WHERE id = :id") suspend fun deleteAccount(id: Long)
    @Query("DELETE FROM margin_accounts") suspend fun clearAccounts()

    @Query("SELECT * FROM margin_entries WHERE deleted = 0 ORDER BY date, id") fun marginEntries(): Flow<List<MarginEntryEntity>>
    @Query("SELECT * FROM margin_entries WHERE deleted = 1 ORDER BY updatedAt DESC") fun deletedMarginEntries(): Flow<List<MarginEntryEntity>>
    @Query("SELECT * FROM margin_entries WHERE id = :id") suspend fun marginEntry(id: Long): MarginEntryEntity?
    @Query("SELECT * FROM margin_entries") suspend fun allMarginEntries(): List<MarginEntryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMarginEntry(e: MarginEntryEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMarginEntries(e: List<MarginEntryEntity>)
    @Query("DELETE FROM margin_entries WHERE id = :id") suspend fun purgeMarginEntry(id: Long)
    @Query("DELETE FROM margin_entries") suspend fun clearMarginEntries()

    // fees
    @Query("SELECT * FROM fee_schedules ORDER BY effectiveFrom") fun fees(): Flow<List<FeeEntity>>
    @Query("SELECT * FROM fee_schedules") suspend fun allFees(): List<FeeEntity>
    @Query("SELECT COUNT(*) FROM fee_schedules") suspend fun feeCount(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFee(e: FeeEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFees(e: List<FeeEntity>)
    @Query("DELETE FROM fee_schedules WHERE id = :id") suspend fun deleteFee(id: Long)
    @Query("DELETE FROM fee_schedules") suspend fun clearFees()

    // companies & prices
    @Query("SELECT * FROM companies ORDER BY symbol") fun companies(): Flow<List<CompanyEntity>>
    @Query("SELECT * FROM companies") suspend fun allCompanies(): List<CompanyEntity>
    @Query("SELECT * FROM companies WHERE symbol = :symbol") suspend fun company(symbol: String): CompanyEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCompany(e: CompanyEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCompanies(e: List<CompanyEntity>)
    @Query("DELETE FROM companies WHERE symbol = :symbol") suspend fun deleteCompany(symbol: String)
    @Query("DELETE FROM companies") suspend fun clearCompanies()

    // watchlist
    @Query("SELECT * FROM watchlist ORDER BY symbol") fun watch(): Flow<List<WatchEntity>>
    @Query("SELECT * FROM watchlist") suspend fun allWatch(): List<WatchEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWatch(e: WatchEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWatchList(e: List<WatchEntity>)
    @Query("DELETE FROM watchlist WHERE id = :id") suspend fun deleteWatch(id: Long)
    @Query("DELETE FROM watchlist") suspend fun clearWatch()

    // audit
    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC, id DESC LIMIT 500") fun audit(): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit_log") suspend fun allAudit(): List<AuditEntity>
    @Insert suspend fun putAudit(e: AuditEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAuditList(e: List<AuditEntity>)
    @Query("DELETE FROM audit_log") suspend fun clearAudit()
}

@Database(
    entities = [CompanyEntity::class, TxnEntity::class, CashEntity::class, MarginAccountEntity::class,
        MarginEntryEntity::class, FeeEntity::class, WatchEntity::class, AuditEntity::class],
    version = 1, exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** Opens the SQLCipher-encrypted database. The key never leaves this device. */
        fun open(context: Context): AppDb {
            System.loadLibrary("sqlcipher")
            val passphrase = Vault.databaseKey(context)
            return Room.databaseBuilder(context, AppDb::class.java, "portfolio.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}

// ---------------------------------------------------------------- key vault

/**
 * Small secrets (the database key, the optional backup password) are encrypted with an AES key
 * that lives in the Android Keystore and cannot be extracted from the device.
 */
object Vault {
    private const val ALIAS = "cse_portfolio_master"
    private const val PREFS = "vault"

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(context: Context, name: String, value: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isNullOrEmpty()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun get(context: Context, name: String): String? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    fun databaseKey(context: Context): ByteArray {
        get(context, "db")?.let { return it.toByteArray(Charsets.US_ASCII) }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        put(context, "db", hex)
        return hex.toByteArray(Charsets.US_ASCII)
    }
}
