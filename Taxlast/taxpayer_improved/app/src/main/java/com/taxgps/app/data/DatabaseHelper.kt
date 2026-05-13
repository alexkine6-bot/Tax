package com.taxgps.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * مساعد قاعدة البيانات SQLite — Singleton
 *
 * مبني على بنية قاعدة بيانات Access: سجلات_الدخل_المقطوع
 * يدعم جميع الأعمدة: السجل، اسم المكلف، اسم الأم، رقم القرار، تاريخ القرار،
 * الملاحظات، المهنة، العنوان، مقدار الضريبة، رقم العمل، الربح الصافي
 */
class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "taxpayers_v4.db"
        private const val DB_VERSION = 5   // رُفع لدعم حقول Access الجديدة

        const val TABLE = "taxpayers"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }

        // أعمدة الجدول
        const val COL_ID              = "_id"
        const val COL_RECORD_NUMBER   = "record_number"      // السجل
        const val COL_NAME            = "name"               // اسم المكلف
        const val COL_MOTHER_NAME     = "mother_name"        // اسم الأم
        const val COL_TAX_NUMBER      = "tax_number"         // الرقم الضريبي
        const val COL_ID_NUMBER       = "id_number"          // رقم الهوية
        const val COL_PHONE           = "phone"              // الهاتف
        const val COL_ADDRESS         = "address"            // العنوان / المنطقة
        const val COL_ACTIVITY_TYPE   = "activity_type"      // المهنة
        const val COL_NOTES           = "notes"              // الملاحظات
        const val COL_TYPE            = "type"               // النوع (قديم/جديد)
        const val COL_STATUS          = "status"             // الحالة
        const val COL_ACCESS_NO       = "access_decision_no" // رقم القرار
        const val COL_DECISION_DATE   = "decision_date"      // تاريخ القرار
        const val COL_TAX_AMOUNT      = "tax_amount"         // مقدار الضريبة
        const val COL_WORK_NUMBER     = "work_number"        // رقم العمل
        const val COL_NET_PROFIT      = "net_profit"         // الربح الصافي
        const val COL_NEIGHBOR_RIGHT  = "neighbor_right"     // الجار الأيمن
        const val COL_NEIGHBOR_LEFT   = "neighbor_left"      // الجار الأيسر
        const val COL_SHOP_DESC       = "shop_description"   // وصف المحل
        const val COL_LATITUDE        = "latitude"
        const val COL_LONGITUDE       = "longitude"
        const val COL_ACCURACY        = "accuracy"
        const val COL_CAPTURED_AT     = "captured_at"
        const val COL_CREATED_AT      = "created_at"
        const val COL_SYNC_STATUS     = "sync_status"
        const val COL_DRIVE_ID        = "google_drive_id"

        private val ALL_COLUMNS = arrayOf(
            COL_ID, COL_RECORD_NUMBER, COL_NAME, COL_MOTHER_NAME,
            COL_TAX_NUMBER, COL_ID_NUMBER, COL_PHONE,
            COL_ADDRESS, COL_ACTIVITY_TYPE, COL_NOTES, COL_TYPE, COL_STATUS,
            COL_ACCESS_NO, COL_DECISION_DATE, COL_TAX_AMOUNT, COL_WORK_NUMBER, COL_NET_PROFIT,
            COL_NEIGHBOR_RIGHT, COL_NEIGHBOR_LEFT, COL_SHOP_DESC,
            COL_LATITUDE, COL_LONGITUDE, COL_ACCURACY,
            COL_CAPTURED_AT, COL_CREATED_AT,
            COL_SYNC_STATUS, COL_DRIVE_ID
        )
    }

    // ─── إنشاء الجدول ────────────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID             INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_RECORD_NUMBER  INTEGER DEFAULT 0,
                $COL_NAME           TEXT NOT NULL,
                $COL_MOTHER_NAME    TEXT,
                $COL_TAX_NUMBER     TEXT,
                $COL_ID_NUMBER      TEXT,
                $COL_PHONE          TEXT,
                $COL_ADDRESS        TEXT,
                $COL_ACTIVITY_TYPE  TEXT,
                $COL_NOTES          TEXT,
                $COL_TYPE           TEXT NOT NULL DEFAULT '${Taxpayer.TYPE_OLD}',
                $COL_STATUS         TEXT DEFAULT '${Taxpayer.STATUS_ACTIVE}',
                $COL_ACCESS_NO      TEXT,
                $COL_DECISION_DATE  TEXT,
                $COL_TAX_AMOUNT     INTEGER DEFAULT 0,
                $COL_WORK_NUMBER    TEXT,
                $COL_NET_PROFIT     INTEGER DEFAULT 0,
                $COL_NEIGHBOR_RIGHT TEXT,
                $COL_NEIGHBOR_LEFT  TEXT,
                $COL_SHOP_DESC      TEXT,
                $COL_LATITUDE       REAL,
                $COL_LONGITUDE      REAL,
                $COL_ACCURACY       REAL,
                $COL_CAPTURED_AT    INTEGER,
                $COL_CREATED_AT     INTEGER,
                $COL_SYNC_STATUS    INTEGER DEFAULT 0,
                $COL_DRIVE_ID       TEXT
            )
        """.trimIndent())

        // فهارس للبحث السريع
        db.execSQL("CREATE INDEX idx_name        ON $TABLE($COL_NAME)")
        db.execSQL("CREATE INDEX idx_access      ON $TABLE($COL_ACCESS_NO)")
        db.execSQL("CREATE INDEX idx_type        ON $TABLE($COL_TYPE)")
        db.execSQL("CREATE INDEX idx_record_num  ON $TABLE($COL_RECORD_NUMBER)")
        db.execSQL("CREATE INDEX idx_address     ON $TABLE($COL_ADDRESS)")
        db.execSQL("CREATE INDEX idx_activity    ON $TABLE($COL_ACTIVITY_TYPE)")
        Log.i(TAG, "Database v$DB_VERSION created")
    }

    // ─── Migrations آمنة ومتسلسلة ────────────────────────────────────────────

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading DB from v$oldVersion to v$newVersion")

        if (oldVersion < 2) migrateTo2(db)
        if (oldVersion < 3) migrateTo3(db)
        if (oldVersion < 4) migrateTo4(db)
        if (oldVersion < 5) migrateTo5(db)

        Log.i(TAG, "Upgrade complete")
    }

    private fun migrateTo2(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NEIGHBOR_RIGHT TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NEIGHBOR_LEFT  TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_SHOP_DESC      TEXT")
    }

    private fun migrateTo3(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_ACCESS_NO   TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_SYNC_STATUS INTEGER DEFAULT 0")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_DRIVE_ID    TEXT")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_access ON $TABLE($COL_ACCESS_NO)")
    }

    private fun migrateTo4(db: SQLiteDatabase) {
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_type ON $TABLE($COL_TYPE)")
    }

    /** v4 → v5: إضافة حقول Access الجديدة */
    private fun migrateTo5(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_RECORD_NUMBER  INTEGER DEFAULT 0")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_MOTHER_NAME    TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_DECISION_DATE  TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_TAX_AMOUNT     INTEGER DEFAULT 0")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_WORK_NUMBER    TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NET_PROFIT     INTEGER DEFAULT 0")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_record_num ON $TABLE($COL_RECORD_NUMBER)")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_address    ON $TABLE($COL_ADDRESS)")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_activity   ON $TABLE($COL_ACTIVITY_TYPE)")
    }

    private fun safeAlter(db: SQLiteDatabase, sql: String) {
        try {
            db.execSQL(sql)
        } catch (e: Exception) {
            Log.w(TAG, "Migration step skipped: ${e.message}")
        }
    }

    // ─── عمليات CRUD ─────────────────────────────────────────────────────────

    suspend fun insertTaxpayerAsync(t: Taxpayer): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE, null, t.toContentValues())
    }

    suspend fun updateTaxpayerAsync(t: Taxpayer): Int = withContext(Dispatchers.IO) {
        writableDatabase.update(TABLE, t.toContentValues(), "$COL_ID=?", arrayOf(t.id.toString()))
    }

    suspend fun deleteTaxpayerAsync(id: Long): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, "$COL_ID=?", arrayOf(id.toString()))
    }

    /** إدخال دفعة (Batch) — أسرع بكثير عند الاستيراد */
    suspend fun insertBatchAsync(taxpayers: List<Taxpayer>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in taxpayers) {
                db.insert(TABLE, null, t.toContentValues())
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        count
    }

    // ─── استعلامات القراءة ───────────────────────────────────────────────────

    suspend fun getAllTaxpayersAsync(
        filter: String = "",
        typeFilter: String = ""
    ): List<Taxpayer> = withContext(Dispatchers.IO) {

        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (filter.isNotBlank()) {
            conditions.add(
                "($COL_NAME LIKE ? OR $COL_TAX_NUMBER LIKE ? " +
                "OR $COL_PHONE LIKE ? OR $COL_ACCESS_NO LIKE ? " +
                "OR $COL_ADDRESS LIKE ? OR $COL_ACTIVITY_TYPE LIKE ? " +
                "OR $COL_RECORD_NUMBER LIKE ?)"
            )
            val q = "%$filter%"
            repeat(7) { args.add(q) }
        }
        if (typeFilter.isNotBlank()) {
            conditions.add("$COL_TYPE=?")
            args.add(typeFilter)
        }

        val selection = if (conditions.isEmpty()) null else conditions.joinToString(" AND ")
        val selArgs  = if (args.isEmpty()) null else args.toTypedArray()

        readableDatabase.query(
            TABLE, ALL_COLUMNS, selection, selArgs,
            null, null, "$COL_CREATED_AT DESC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTaxpayer()) }
        }
    }

    suspend fun getTaxpayersWithLocationAsync(): List<Taxpayer> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE, ALL_COLUMNS,
            "$COL_LATITUDE IS NOT NULL AND $COL_LONGITUDE IS NOT NULL",
            null, null, null, "$COL_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTaxpayer()) }
        }
    }

    suspend fun getTaxpayerByIdAsync(id: Long): Taxpayer? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE, ALL_COLUMNS, "$COL_ID=?", arrayOf(id.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTaxpayer() else null
        }
    }

    /** بحث بالاسم + رقم القرار (للاستيراد — تفادي التكرار) */
    suspend fun findTaxpayerForUpdateAsync(name: String, decisionNo: String): Taxpayer? =
        withContext(Dispatchers.IO) {
            readableDatabase.query(
                TABLE, ALL_COLUMNS,
                "$COL_NAME=? AND $COL_ACCESS_NO=?",
                arrayOf(name, decisionNo),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toTaxpayer() else null
            }
        }

    /** بحث بالاسم + رقم السجل (للاستيراد من Access) */
    suspend fun findByNameAndRecordAsync(name: String, recordNumber: Int): Taxpayer? =
        withContext(Dispatchers.IO) {
            readableDatabase.query(
                TABLE, ALL_COLUMNS,
                "$COL_NAME=? AND $COL_RECORD_NUMBER=?",
                arrayOf(name, recordNumber.toString()),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toTaxpayer() else null
            }
        }

    /** إحصائيات الشاشة الرئيسية */
    suspend fun getStatsAsync(): TaxpayerStats = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN $COL_TYPE='${Taxpayer.TYPE_OLD}' THEN 1 ELSE 0 END) AS old_count,
                SUM(CASE WHEN $COL_TYPE='${Taxpayer.TYPE_NEW}' THEN 1 ELSE 0 END) AS new_count,
                SUM(CASE WHEN $COL_LATITUDE IS NOT NULL THEN 1 ELSE 0 END) AS with_location,
                SUM($COL_TAX_AMOUNT) AS total_tax,
                SUM($COL_NET_PROFIT) AS total_profit
            FROM $TABLE
            """.trimIndent(), null
        )
        cursor.use {
            if (it.moveToFirst()) {
                TaxpayerStats(
                    total        = it.getInt(it.getColumnIndexOrThrow("total")),
                    oldCount     = it.getInt(it.getColumnIndexOrThrow("old_count")),
                    newCount     = it.getInt(it.getColumnIndexOrThrow("new_count")),
                    withLocation = it.getInt(it.getColumnIndexOrThrow("with_location")),
                    totalTax     = it.getLong(it.getColumnIndexOrThrow("total_tax")),
                    totalProfit  = it.getLong(it.getColumnIndexOrThrow("total_profit"))
                )
            } else TaxpayerStats()
        }
    }

    /** عدد السجلات الحالية (للتحقق قبل الاستيراد) */
    suspend fun getCountAsync(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** حذف جميع السجلات (إعادة ضبط) */
    suspend fun deleteAllAsync(): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, null, null)
    }

    // ─── تحويل ContentValues / Cursor ────────────────────────────────────────

    private fun Taxpayer.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_RECORD_NUMBER,  recordNumber)
        put(COL_NAME,           name)
        put(COL_MOTHER_NAME,    motherName)
        put(COL_TAX_NUMBER,     taxNumber)
        put(COL_ID_NUMBER,      idNumber)
        put(COL_PHONE,          phone)
        put(COL_ADDRESS,        address)
        put(COL_ACTIVITY_TYPE,  activityType)
        put(COL_NOTES,          notes)
        put(COL_TYPE,           type)
        put(COL_STATUS,         status)
        put(COL_ACCESS_NO,      accessDecisionNo)
        put(COL_DECISION_DATE,  decisionDate)
        put(COL_TAX_AMOUNT,     taxAmount)
        put(COL_WORK_NUMBER,    workNumber)
        put(COL_NET_PROFIT,     netProfit)
        put(COL_NEIGHBOR_RIGHT, neighborRight)
        put(COL_NEIGHBOR_LEFT,  neighborLeft)
        put(COL_SHOP_DESC,      shopDescription)
        put(COL_LATITUDE,       latitude)
        put(COL_LONGITUDE,      longitude)
        put(COL_ACCURACY,       accuracy)
        put(COL_CAPTURED_AT,    capturedAt)
        put(COL_CREATED_AT,     createdAt)
        put(COL_SYNC_STATUS,    syncStatus)
        put(COL_DRIVE_ID,       googleDriveId)
    }

    private fun Cursor.toTaxpayer(): Taxpayer {
        fun str(col: String): String {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getString(idx) ?: "" else ""
        }
        fun lng(col: String): Long {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else 0L
        }
        fun int(col: String): Int {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getInt(idx) else 0
        }
        fun dbl(col: String): Double? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getDouble(idx) else null
        }
        fun flt(col: String): Float? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getFloat(idx) else null
        }
        fun lngNull(col: String): Long? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
        }

        return Taxpayer(
            id              = lng(COL_ID),
            recordNumber    = int(COL_RECORD_NUMBER),
            name            = str(COL_NAME),
            motherName      = str(COL_MOTHER_NAME),
            taxNumber       = str(COL_TAX_NUMBER),
            idNumber        = str(COL_ID_NUMBER),
            phone           = str(COL_PHONE),
            address         = str(COL_ADDRESS),
            activityType    = str(COL_ACTIVITY_TYPE),
            notes           = str(COL_NOTES),
            type            = str(COL_TYPE).ifBlank { Taxpayer.TYPE_OLD },
            status          = str(COL_STATUS).ifBlank { Taxpayer.STATUS_ACTIVE },
            accessDecisionNo = str(COL_ACCESS_NO),
            decisionDate    = str(COL_DECISION_DATE),
            taxAmount       = lng(COL_TAX_AMOUNT),
            workNumber      = str(COL_WORK_NUMBER),
            netProfit       = lng(COL_NET_PROFIT),
            neighborRight   = str(COL_NEIGHBOR_RIGHT),
            neighborLeft    = str(COL_NEIGHBOR_LEFT),
            shopDescription = str(COL_SHOP_DESC),
            latitude        = dbl(COL_LATITUDE),
            longitude       = dbl(COL_LONGITUDE),
            accuracy        = flt(COL_ACCURACY),
            capturedAt      = lngNull(COL_CAPTURED_AT),
            createdAt       = lng(COL_CREATED_AT),
            syncStatus      = int(COL_SYNC_STATUS),
            googleDriveId   = str(COL_DRIVE_ID)
        )
    }
}

/** نموذج إحصائيات */
data class TaxpayerStats(
    val total: Int        = 0,
    val oldCount: Int     = 0,
    val newCount: Int     = 0,
    val withLocation: Int = 0,
    val totalTax: Long    = 0,
    val totalProfit: Long = 0
)
