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
 * مساعد قاعدة البيانات SQLite
 *
 * التحسينات:
 * - migration آمن ومتسلسل بدلاً من try/catch فارغ
 * - إضافة deleteTaxpayerAsync (كانت معلّقة)
 * - إضافة getTaxpayersWithLocation() لعرض الخريطة
 * - إضافة getStats() لإحصائيات الشاشة الرئيسية
 * - تسجيل الأخطاء بـ Log بدلاً من ابتلاعها
 */
class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "taxpayers_v3.db"
        private const val DB_VERSION = 4   // رُفع من 3 إلى 4

        const val TABLE = "taxpayers"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        /**
         * Singleton pattern — يضمن وجود instance واحدة فقط لتفادي مشاكل SQLite المتزامنة
         */
        fun getInstance(context: Context): DatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }

        // أعمدة الجدول
        const val COL_ID              = "_id"
        const val COL_NAME            = "name"
        const val COL_TAX_NUMBER      = "tax_number"
        const val COL_ID_NUMBER       = "id_number"
        const val COL_PHONE           = "phone"
        const val COL_ADDRESS         = "address"
        const val COL_ACTIVITY_TYPE   = "activity_type"
        const val COL_NOTES           = "notes"
        const val COL_TYPE            = "type"
        const val COL_STATUS          = "status"
        const val COL_NEIGHBOR_RIGHT  = "neighbor_right"
        const val COL_NEIGHBOR_LEFT   = "neighbor_left"
        const val COL_SHOP_DESC       = "shop_description"
        const val COL_LATITUDE        = "latitude"       // REAL
        const val COL_LONGITUDE       = "longitude"      // REAL
        const val COL_ACCURACY        = "accuracy"       // REAL
        const val COL_CAPTURED_AT     = "captured_at"
        const val COL_CREATED_AT      = "created_at"
        const val COL_ACCESS_NO       = "access_decision_no"
        const val COL_SYNC_STATUS     = "sync_status"
        const val COL_DRIVE_ID        = "google_drive_id"

        private val ALL_COLUMNS = arrayOf(
            COL_ID, COL_NAME, COL_TAX_NUMBER, COL_ID_NUMBER, COL_PHONE,
            COL_ADDRESS, COL_ACTIVITY_TYPE, COL_NOTES, COL_TYPE, COL_STATUS,
            COL_NEIGHBOR_RIGHT, COL_NEIGHBOR_LEFT, COL_SHOP_DESC,
            COL_LATITUDE, COL_LONGITUDE, COL_ACCURACY,
            COL_CAPTURED_AT, COL_CREATED_AT,
            COL_ACCESS_NO, COL_SYNC_STATUS, COL_DRIVE_ID
        )
    }

    // ─── إنشاء الجدول ────────────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID             INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME           TEXT NOT NULL,
                $COL_TAX_NUMBER     TEXT,
                $COL_ID_NUMBER      TEXT,
                $COL_PHONE          TEXT,
                $COL_ADDRESS        TEXT,
                $COL_ACTIVITY_TYPE  TEXT,
                $COL_NOTES          TEXT,
                $COL_TYPE           TEXT NOT NULL DEFAULT '${Taxpayer.TYPE_OLD}',
                $COL_STATUS         TEXT DEFAULT '${Taxpayer.STATUS_ACTIVE}',
                $COL_NEIGHBOR_RIGHT TEXT,
                $COL_NEIGHBOR_LEFT  TEXT,
                $COL_SHOP_DESC      TEXT,
                $COL_LATITUDE       REAL,
                $COL_LONGITUDE      REAL,
                $COL_ACCURACY       REAL,
                $COL_CAPTURED_AT    INTEGER,
                $COL_CREATED_AT     INTEGER,
                $COL_ACCESS_NO      TEXT,
                $COL_SYNC_STATUS    INTEGER DEFAULT 0,
                $COL_DRIVE_ID       TEXT
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_name   ON $TABLE($COL_NAME)")
        db.execSQL("CREATE INDEX idx_access ON $TABLE($COL_ACCESS_NO)")
        db.execSQL("CREATE INDEX idx_type   ON $TABLE($COL_TYPE)")
        Log.i(TAG, "Database v$DB_VERSION created")
    }

    // ─── Migrations آمنة ومتسلسلة ────────────────────────────────────────────

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading DB from v$oldVersion to v$newVersion")

        // كل إصدار يُطبَّق بالترتيب حتى نصل للإصدار الحالي
        if (oldVersion < 2) migrateTo2(db)
        if (oldVersion < 3) migrateTo3(db)
        if (oldVersion < 4) migrateTo4(db)

        Log.i(TAG, "Upgrade complete")
    }

    /** v1 → v2: إضافة حقول الجار ووصف المحل */
    private fun migrateTo2(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NEIGHBOR_RIGHT TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NEIGHBOR_LEFT  TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_SHOP_DESC      TEXT")
    }

    /** v2 → v3: إضافة حقول Access والمزامنة */
    private fun migrateTo3(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_ACCESS_NO   TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_SYNC_STATUS INTEGER DEFAULT 0")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_DRIVE_ID    TEXT")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_access ON $TABLE($COL_ACCESS_NO)")
    }

    /** v3 → v4: تأكيد وجود فهرس النوع (كان ناقصاً في الإصدار السابق) */
    private fun migrateTo4(db: SQLiteDatabase) {
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_type ON $TABLE($COL_TYPE)")
    }

    /** تنفيذ أمر SQL بأمان مع تسجيل الخطأ إن وجد */
    private fun safeAlter(db: SQLiteDatabase, sql: String) {
        try {
            db.execSQL(sql)
        } catch (e: Exception) {
            // عمود موجود مسبقاً أو فهرس مكرر — نسجّل ونكمل
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

    /**
     * حذف مكلف — كانت هذه الوظيفة معلّقة بتعليق في الكود الأصلي
     */
    suspend fun deleteTaxpayerAsync(id: Long): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, "$COL_ID=?", arrayOf(id.toString()))
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
                "OR $COL_PHONE LIKE ? OR $COL_ACCESS_NO LIKE ?)"
            )
            val q = "%$filter%"
            repeat(4) { args.add(q) }
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

    /** جلب المكلفين الذين لديهم إحداثيات — للخريطة الجماعية */
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

    /** بحث للربط مع بيانات Access: الاسم + رقم القرار */
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

    /** إحصائيات سريعة للشاشة الرئيسية */
    suspend fun getStatsAsync(): TaxpayerStats = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN $COL_TYPE='${Taxpayer.TYPE_OLD}' THEN 1 ELSE 0 END) AS old_count,
                SUM(CASE WHEN $COL_TYPE='${Taxpayer.TYPE_NEW}' THEN 1 ELSE 0 END) AS new_count,
                SUM(CASE WHEN $COL_LATITUDE IS NOT NULL THEN 1 ELSE 0 END) AS with_location
            FROM $TABLE
            """.trimIndent(), null
        )
        cursor.use {
            if (it.moveToFirst()) {
                TaxpayerStats(
                    total        = it.getInt(it.getColumnIndexOrThrow("total")),
                    oldCount     = it.getInt(it.getColumnIndexOrThrow("old_count")),
                    newCount     = it.getInt(it.getColumnIndexOrThrow("new_count")),
                    withLocation = it.getInt(it.getColumnIndexOrThrow("with_location"))
                )
            } else TaxpayerStats()
        }
    }

    // ─── تحويل ContentValues / Cursor ────────────────────────────────────────

    private fun Taxpayer.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_NAME,           name)
        put(COL_TAX_NUMBER,     taxNumber)
        put(COL_ID_NUMBER,      idNumber)
        put(COL_PHONE,          phone)
        put(COL_ADDRESS,        address)
        put(COL_ACTIVITY_TYPE,  activityType)
        put(COL_NOTES,          notes)
        put(COL_TYPE,           type)
        put(COL_STATUS,         status)
        put(COL_NEIGHBOR_RIGHT, neighborRight)
        put(COL_NEIGHBOR_LEFT,  neighborLeft)
        put(COL_SHOP_DESC,      shopDescription)
        put(COL_LATITUDE,       latitude)
        put(COL_LONGITUDE,      longitude)
        put(COL_ACCURACY,       accuracy)
        put(COL_CAPTURED_AT,    capturedAt)
        put(COL_CREATED_AT,     createdAt)
        put(COL_ACCESS_NO,      accessDecisionNo)
        put(COL_SYNC_STATUS,    syncStatus)
        put(COL_DRIVE_ID,       googleDriveId)
    }

    private fun Cursor.toTaxpayer(): Taxpayer {
        fun str(col: String) = getString(getColumnIndexOrThrow(col)) ?: ""
        fun lng(col: String) = getLong(getColumnIndexOrThrow(col))
        fun int(col: String) = getInt(getColumnIndexOrThrow(col))
        fun dbl(col: String, nullable: Boolean = false): Double? {
            val idx = getColumnIndexOrThrow(col)
            return if (nullable && isNull(idx)) null else getDouble(idx)
        }
        fun flt(col: String): Float? {
            val idx = getColumnIndexOrThrow(col)
            return if (isNull(idx)) null else getFloat(idx)
        }
        fun lngNull(col: String): Long? {
            val idx = getColumnIndexOrThrow(col)
            return if (isNull(idx)) null else getLong(idx)
        }

        return Taxpayer(
            id              = lng(COL_ID),
            name            = str(COL_NAME),
            taxNumber       = str(COL_TAX_NUMBER),
            idNumber        = str(COL_ID_NUMBER),
            phone           = str(COL_PHONE),
            address         = str(COL_ADDRESS),
            activityType    = str(COL_ACTIVITY_TYPE),
            notes           = str(COL_NOTES),
            type            = str(COL_TYPE).ifBlank { Taxpayer.TYPE_OLD },
            status          = str(COL_STATUS).ifBlank { Taxpayer.STATUS_ACTIVE },
            neighborRight   = str(COL_NEIGHBOR_RIGHT),
            neighborLeft    = str(COL_NEIGHBOR_LEFT),
            shopDescription = str(COL_SHOP_DESC),
            latitude        = dbl(COL_LATITUDE, nullable = true),
            longitude       = dbl(COL_LONGITUDE, nullable = true),
            accuracy        = flt(COL_ACCURACY),
            capturedAt      = lngNull(COL_CAPTURED_AT),
            createdAt       = lng(COL_CREATED_AT),
            accessDecisionNo = str(COL_ACCESS_NO),
            syncStatus      = int(COL_SYNC_STATUS),
            googleDriveId   = str(COL_DRIVE_ID)
        )
    }
}

/** نموذج إحصائيات لعرضها في الشاشة الرئيسية */
data class TaxpayerStats(
    val total: Int        = 0,
    val oldCount: Int     = 0,
    val newCount: Int     = 0,
    val withLocation: Int = 0
)
