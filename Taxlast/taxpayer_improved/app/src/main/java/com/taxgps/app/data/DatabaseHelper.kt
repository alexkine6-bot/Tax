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
 * يدعم جميع الأعمدة + جدول المعالم المرجعية للخريطة
 */
class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "taxpayers_v4.db"
        private const val DB_VERSION = 7   // رُفع لدعم رقم العقار والصور

        const val TABLE = "taxpayers"
        const val TABLE_LANDMARKS = "landmarks"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }

        // أعمدة جدول المكلفين
        const val COL_ID              = "_id"
        const val COL_RECORD_NUMBER   = "record_number"
        const val COL_NAME            = "name"
        const val COL_MOTHER_NAME     = "mother_name"
        const val COL_TAX_NUMBER      = "tax_number"
        const val COL_ID_NUMBER       = "id_number"
        const val COL_PHONE           = "phone"
        const val COL_ADDRESS         = "address"
        const val COL_ACTIVITY_TYPE   = "activity_type"
        const val COL_NOTES           = "notes"
        const val COL_TYPE            = "type"
        const val COL_STATUS          = "status"
        const val COL_ACCESS_NO       = "access_decision_no"
        const val COL_DECISION_DATE   = "decision_date"
        const val COL_TAX_AMOUNT      = "tax_amount"
        const val COL_WORK_NUMBER     = "work_number"
        const val COL_NET_PROFIT      = "net_profit"
        const val COL_NEIGHBOR_RIGHT  = "neighbor_right"
        const val COL_NEIGHBOR_LEFT   = "neighbor_left"
        const val COL_SHOP_DESC       = "shop_description"
        const val COL_PROPERTY_NUMBER = "property_number"
        const val COL_PHOTOS          = "photos"
        const val COL_LATITUDE        = "latitude"
        const val COL_LONGITUDE       = "longitude"
        const val COL_ACCURACY        = "accuracy"
        const val COL_CAPTURED_AT     = "captured_at"
        const val COL_CREATED_AT      = "created_at"
        const val COL_SYNC_STATUS     = "sync_status"
        const val COL_DRIVE_ID        = "google_drive_id"

        // أعمدة جدول المعالم المرجعية
        const val COL_LM_ID           = "_id"
        const val COL_LM_NAME         = "name"
        const val COL_LM_TYPE         = "type"
        const val COL_LM_DESCRIPTION  = "description"
        const val COL_LM_AREA         = "area"
        const val COL_LM_LATITUDE     = "latitude"
        const val COL_LM_LONGITUDE    = "longitude"
        const val COL_LM_ACCURACY     = "accuracy"
        const val COL_LM_IS_MAIN      = "is_main_reference"
        const val COL_LM_CREATED_AT   = "created_at"

        private val ALL_COLUMNS = arrayOf(
            COL_ID, COL_RECORD_NUMBER, COL_NAME, COL_MOTHER_NAME,
            COL_TAX_NUMBER, COL_ID_NUMBER, COL_PHONE,
            COL_ADDRESS, COL_ACTIVITY_TYPE, COL_NOTES, COL_TYPE, COL_STATUS,
            COL_ACCESS_NO, COL_DECISION_DATE, COL_TAX_AMOUNT, COL_WORK_NUMBER, COL_NET_PROFIT,
            COL_NEIGHBOR_RIGHT, COL_NEIGHBOR_LEFT, COL_SHOP_DESC, COL_PROPERTY_NUMBER, COL_PHOTOS,
            COL_LATITUDE, COL_LONGITUDE, COL_ACCURACY,
            COL_CAPTURED_AT, COL_CREATED_AT,
            COL_SYNC_STATUS, COL_DRIVE_ID
        )

        private val ALL_LANDMARK_COLUMNS = arrayOf(
            COL_LM_ID, COL_LM_NAME, COL_LM_TYPE, COL_LM_DESCRIPTION,
            COL_LM_AREA, COL_LM_LATITUDE, COL_LM_LONGITUDE,
            COL_LM_ACCURACY, COL_LM_IS_MAIN, COL_LM_CREATED_AT
        )
    }

    // ─── إنشاء الجداول ───────────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        // جدول المكلفين
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
                $COL_PROPERTY_NUMBER TEXT,
                $COL_PHOTOS         TEXT,
                $COL_LATITUDE       REAL,
                $COL_LONGITUDE      REAL,
                $COL_ACCURACY       REAL,
                $COL_CAPTURED_AT    INTEGER,
                $COL_CREATED_AT     INTEGER,
                $COL_SYNC_STATUS    INTEGER DEFAULT 0,
                $COL_DRIVE_ID       TEXT
            )
        """.trimIndent())

        // فهارس المكلفين
        db.execSQL("CREATE INDEX idx_name        ON $TABLE($COL_NAME)")
        db.execSQL("CREATE INDEX idx_access      ON $TABLE($COL_ACCESS_NO)")
        db.execSQL("CREATE INDEX idx_type        ON $TABLE($COL_TYPE)")
        db.execSQL("CREATE INDEX idx_record_num  ON $TABLE($COL_RECORD_NUMBER)")
        db.execSQL("CREATE INDEX idx_address     ON $TABLE($COL_ADDRESS)")
        db.execSQL("CREATE INDEX idx_activity    ON $TABLE($COL_ACTIVITY_TYPE)")

        // جدول المعالم المرجعية
        createLandmarksTable(db)

        Log.i(TAG, "Database v$DB_VERSION created")
    }

    private fun createLandmarksTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_LANDMARKS (
                $COL_LM_ID          INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LM_NAME        TEXT NOT NULL,
                $COL_LM_TYPE        TEXT NOT NULL DEFAULT '${Landmark.TYPE_OTHER}',
                $COL_LM_DESCRIPTION TEXT,
                $COL_LM_AREA        TEXT,
                $COL_LM_LATITUDE    REAL NOT NULL,
                $COL_LM_LONGITUDE   REAL NOT NULL,
                $COL_LM_ACCURACY    REAL,
                $COL_LM_IS_MAIN     INTEGER DEFAULT 0,
                $COL_LM_CREATED_AT  INTEGER
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lm_name ON $TABLE_LANDMARKS($COL_LM_NAME)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lm_type ON $TABLE_LANDMARKS($COL_LM_TYPE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lm_area ON $TABLE_LANDMARKS($COL_LM_AREA)")
    }

    // ─── Migrations آمنة ومتسلسلة ────────────────────────────────────────────

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading DB from v$oldVersion to v$newVersion")

        if (oldVersion < 2) migrateTo2(db)
        if (oldVersion < 3) migrateTo3(db)
        if (oldVersion < 4) migrateTo4(db)
        if (oldVersion < 5) migrateTo5(db)
        if (oldVersion < 6) migrateTo6(db)
        if (oldVersion < 7) migrateTo7(db)

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

    /** v5 → v6: إضافة جدول المعالم المرجعية */
    private fun migrateTo6(db: SQLiteDatabase) {
        createLandmarksTable(db)
    }

    /** v6 → v7: إضافة رقم العقار والصور */
    private fun migrateTo7(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_PROPERTY_NUMBER TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_PHOTOS TEXT")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_property ON $TABLE($COL_PROPERTY_NUMBER)")
    }

    private fun safeAlter(db: SQLiteDatabase, sql: String) {
        try {
            db.execSQL(sql)
        } catch (e: Exception) {
            Log.w(TAG, "Migration step skipped: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── عمليات CRUD للمكلفين ────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun insertTaxpayerAsync(t: Taxpayer): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE, null, t.toContentValues())
    }

    suspend fun updateTaxpayerAsync(t: Taxpayer): Int = withContext(Dispatchers.IO) {
        writableDatabase.update(TABLE, t.toContentValues(), "$COL_ID=?", arrayOf(t.id.toString()))
    }

    suspend fun deleteTaxpayerAsync(id: Long): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, "$COL_ID=?", arrayOf(id.toString()))
    }

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
        typeFilter: String = "",
        limit: Int = 500,
        offset: Int = 0
    ): List<Taxpayer> = withContext(Dispatchers.IO) {

        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (filter.isNotBlank()) {
            // بحث محسّن: يدعم البحث الجزئي بكفاءة
            // استخدام GLOB بدلاً من LIKE للحروف العربية أسرع في بعض الحالات
            conditions.add(
                "($COL_NAME LIKE ? OR $COL_TAX_NUMBER LIKE ? " +
                "OR $COL_PHONE LIKE ? OR $COL_ACCESS_NO LIKE ? " +
                "OR $COL_ADDRESS LIKE ? OR $COL_ACTIVITY_TYPE LIKE ? " +
                "OR $COL_RECORD_NUMBER LIKE ? OR $COL_PROPERTY_NUMBER LIKE ?)"
            )
            val q = "%$filter%"
            repeat(8) { args.add(q) }
        }
        if (typeFilter.isNotBlank()) {
            conditions.add("$COL_TYPE=?")
            args.add(typeFilter)
        }

        val selection = if (conditions.isEmpty()) null else conditions.joinToString(" AND ")
        val selArgs  = if (args.isEmpty()) null else args.toTypedArray()

        // LIMIT مهم جداً لتحسين الأداء مع آلاف السجلات
        readableDatabase.query(
            TABLE, ALL_COLUMNS, selection, selArgs,
            null, null, "$COL_NAME ASC", "$limit OFFSET $offset"
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

    /** جلب المكلفين في منطقة معينة مع إحداثيات */
    suspend fun getTaxpayersInAreaAsync(area: String): List<Taxpayer> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE, ALL_COLUMNS,
            "$COL_ADDRESS LIKE ? AND $COL_LATITUDE IS NOT NULL AND $COL_LONGITUDE IS NOT NULL",
            arrayOf("%$area%"),
            null, null, "$COL_NAME ASC"
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

    suspend fun getCountAsync(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    suspend fun deleteAllAsync(): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, null, null)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── عمليات CRUD للمعالم المرجعية ────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun insertLandmarkAsync(landmark: Landmark): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE_LANDMARKS, null, landmark.toContentValues())
    }

    suspend fun updateLandmarkAsync(landmark: Landmark): Int = withContext(Dispatchers.IO) {
        writableDatabase.update(
            TABLE_LANDMARKS, landmark.toContentValues(),
            "$COL_LM_ID=?", arrayOf(landmark.id.toString())
        )
    }

    suspend fun deleteLandmarkAsync(id: Long): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_LANDMARKS, "$COL_LM_ID=?", arrayOf(id.toString()))
    }

    suspend fun getAllLandmarksAsync(): List<Landmark> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            null, null, null, null, "$COL_LM_IS_MAIN DESC, $COL_LM_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toLandmark()) }
        }
    }

    suspend fun getLandmarksByTypeAsync(type: String): List<Landmark> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            "$COL_LM_TYPE=?", arrayOf(type),
            null, null, "$COL_LM_IS_MAIN DESC, $COL_LM_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toLandmark()) }
        }
    }

    suspend fun getMainLandmarksAsync(): List<Landmark> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            "$COL_LM_IS_MAIN=1", null,
            null, null, "$COL_LM_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toLandmark()) }
        }
    }

    suspend fun getLandmarkByIdAsync(id: Long): Landmark? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            "$COL_LM_ID=?", arrayOf(id.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toLandmark() else null
        }
    }

    suspend fun getLandmarkCountAsync(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_LANDMARKS", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // ─── تحويل ContentValues / Cursor (مكلفين) ───────────────────────────────

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
        put(COL_PROPERTY_NUMBER, propertyNumber)
        put(COL_PHOTOS,         photos)
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
            propertyNumber  = str(COL_PROPERTY_NUMBER),
            photos          = str(COL_PHOTOS),
            latitude        = dbl(COL_LATITUDE),
            longitude       = dbl(COL_LONGITUDE),
            accuracy        = flt(COL_ACCURACY),
            capturedAt      = lngNull(COL_CAPTURED_AT),
            createdAt       = lng(COL_CREATED_AT),
            syncStatus      = int(COL_SYNC_STATUS),
            googleDriveId   = str(COL_DRIVE_ID)
        )
    }

    // ─── تحويل ContentValues / Cursor (معالم) ────────────────────────────────

    private fun Landmark.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_LM_NAME,        name)
        put(COL_LM_TYPE,        type)
        put(COL_LM_DESCRIPTION, description)
        put(COL_LM_AREA,        area)
        put(COL_LM_LATITUDE,    latitude)
        put(COL_LM_LONGITUDE,   longitude)
        put(COL_LM_ACCURACY,    accuracy)
        put(COL_LM_IS_MAIN,     if (isMainReference) 1 else 0)
        put(COL_LM_CREATED_AT,  createdAt)
    }

    private fun Cursor.toLandmark(): Landmark {
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
        fun dbl(col: String): Double {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getDouble(idx) else 0.0
        }
        fun flt(col: String): Float? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getFloat(idx) else null
        }

        return Landmark(
            id              = lng(COL_LM_ID),
            name            = str(COL_LM_NAME),
            type            = str(COL_LM_TYPE).ifBlank { Landmark.TYPE_OTHER },
            description     = str(COL_LM_DESCRIPTION),
            area            = str(COL_LM_AREA),
            latitude        = dbl(COL_LM_LATITUDE),
            longitude       = dbl(COL_LM_LONGITUDE),
            accuracy        = flt(COL_LM_ACCURACY),
            isMainReference = int(COL_LM_IS_MAIN) == 1,
            createdAt       = lng(COL_LM_CREATED_AT)
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
