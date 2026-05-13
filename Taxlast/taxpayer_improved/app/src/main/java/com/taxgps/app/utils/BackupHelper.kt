package com.taxgps.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Landmark
import com.taxgps.app.data.Taxpayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * مساعد النسخ الاحتياطي والاستعادة
 *
 * يدعم:
 * - تصدير كامل لقاعدة البيانات (مكلفين + معالم) بصيغة JSON مضغوط (.taxbackup)
 * - تصدير الصور المرتبطة ضمن ملف ZIP
 * - استيراد النسخة الاحتياطية واستعادة كل البيانات
 * - تنسيق الملف: ZIP يحتوي data.json + مجلد photos/
 */
class BackupHelper(
    private val context: Context,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "BackupHelper"
        const val BACKUP_EXTENSION = ".taxbackup"
        private const val DATA_FILE = "data.json"
        private const val PHOTOS_DIR = "photos/"
        private const val VERSION_KEY = "backup_version"
        private const val BACKUP_VERSION = 2
    }

    interface BackupListener {
        fun onProgress(message: String, percent: Int)
        fun onSuccess(message: String)
        fun onError(error: String)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── تصدير النسخة الاحتياطية ─────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun exportBackup(
        outputUri: Uri,
        listener: BackupListener
    ) = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                listener.onProgress("جاري تجميع البيانات...", 10)
            }

            // جمع كل البيانات
            val taxpayers = db.getAllTaxpayersAsync(limit = 100000)
            val landmarks = db.getAllLandmarksAsync()

            withContext(Dispatchers.Main) {
                listener.onProgress("جاري إنشاء ملف النسخة...", 30)
            }

            // بناء JSON
            val json = JSONObject().apply {
                put(VERSION_KEY, BACKUP_VERSION)
                put("created_at", System.currentTimeMillis())
                put("created_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                put("taxpayer_count", taxpayers.size)
                put("landmark_count", landmarks.size)

                // مصفوفة المكلفين
                put("taxpayers", JSONArray().apply {
                    for (t in taxpayers) {
                        put(taxpayerToJson(t))
                    }
                })

                // مصفوفة المعالم
                put("landmarks", JSONArray().apply {
                    for (l in landmarks) {
                        put(landmarkToJson(l))
                    }
                })
            }

            withContext(Dispatchers.Main) {
                listener.onProgress("جاري كتابة الملف...", 60)
            }

            // كتابة ZIP
            val outputStream = context.contentResolver.openOutputStream(outputUri)
            if (outputStream == null) {
                withContext(Dispatchers.Main) { listener.onError("لا يمكن فتح الملف للكتابة") }
                return@withContext
            }

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
                // كتابة data.json
                zip.putNextEntry(ZipEntry(DATA_FILE))
                zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // نسخ الصور المرتبطة
                var photosCopied = 0
                for (t in taxpayers) {
                    if (t.photos.isBlank()) continue
                    val photoPaths = t.photos.split("|").filter { it.isNotBlank() }
                    for (path in photoPaths) {
                        try {
                            val file = File(path)
                            if (file.exists()) {
                                zip.putNextEntry(ZipEntry("$PHOTOS_DIR${file.name}"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                                photosCopied++
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to backup photo: $path")
                        }
                    }
                }

                Log.i(TAG, "Backup complete: ${taxpayers.size} taxpayers, ${landmarks.size} landmarks, $photosCopied photos")
            }

            withContext(Dispatchers.Main) {
                listener.onProgress("تم بنجاح!", 100)
                listener.onSuccess(
                    "تم إنشاء النسخة الاحتياطية بنجاح\n" +
                    "المكلفون: ${taxpayers.size}\n" +
                    "المعالم: ${landmarks.size}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            withContext(Dispatchers.Main) {
                listener.onError("فشل التصدير: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── استيراد النسخة الاحتياطية ───────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun importBackup(
        inputUri: Uri,
        replaceExisting: Boolean,
        listener: BackupListener
    ) = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                listener.onProgress("جاري قراءة الملف...", 10)
            }

            val inputStream = context.contentResolver.openInputStream(inputUri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) { listener.onError("لا يمكن فتح الملف") }
                return@withContext
            }

            var jsonData: String? = null
            val photosDir = File(context.filesDir, "taxpayer_photos")
            if (!photosDir.exists()) photosDir.mkdirs()

            // قراءة ZIP
            ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == DATA_FILE -> {
                            jsonData = zip.bufferedReader(Charsets.UTF_8).readText()
                        }
                        entry.name.startsWith(PHOTOS_DIR) && !entry.isDirectory -> {
                            // استعادة الصور
                            val fileName = entry.name.removePrefix(PHOTOS_DIR)
                            val photoFile = File(photosDir, fileName)
                            FileOutputStream(photoFile).use { fos ->
                                zip.copyTo(fos)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (jsonData == null) {
                withContext(Dispatchers.Main) {
                    listener.onError("الملف لا يحتوي على بيانات صالحة")
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                listener.onProgress("جاري تحليل البيانات...", 30)
            }

            val json = JSONObject(jsonData!!)
            val version = json.optInt(VERSION_KEY, 1)

            // استيراد المكلفين
            val taxpayersArray = json.getJSONArray("taxpayers")
            val landmarksArray = json.optJSONArray("landmarks") ?: JSONArray()

            withContext(Dispatchers.Main) {
                listener.onProgress("جاري استيراد ${taxpayersArray.length()} مكلف...", 50)
            }

            if (replaceExisting) {
                db.deleteAllAsync()
            }

            // استيراد المكلفين بدفعات
            val taxpayerBatch = mutableListOf<Taxpayer>()
            for (i in 0 until taxpayersArray.length()) {
                val obj = taxpayersArray.getJSONObject(i)
                val taxpayer = jsonToTaxpayer(obj, photosDir.absolutePath)
                taxpayerBatch.add(taxpayer)

                if (taxpayerBatch.size >= 200) {
                    db.insertBatchAsync(taxpayerBatch)
                    taxpayerBatch.clear()

                    withContext(Dispatchers.Main) {
                        val pct = 50 + (i * 40 / taxpayersArray.length())
                        listener.onProgress("مكلف ${i + 1} من ${taxpayersArray.length()}", pct)
                    }
                }
            }
            if (taxpayerBatch.isNotEmpty()) {
                db.insertBatchAsync(taxpayerBatch)
            }

            // استيراد المعالم
            for (i in 0 until landmarksArray.length()) {
                val obj = landmarksArray.getJSONObject(i)
                val landmark = jsonToLandmark(obj)
                db.insertLandmarkAsync(landmark)
            }

            withContext(Dispatchers.Main) {
                listener.onProgress("تم!", 100)
                listener.onSuccess(
                    "تم استعادة النسخة الاحتياطية بنجاح\n" +
                    "المكلفون: ${taxpayersArray.length()}\n" +
                    "المعالم: ${landmarksArray.length()}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            withContext(Dispatchers.Main) {
                listener.onError("فشل الاستيراد: ${e.message}")
            }
        }
    }

    // ─── تحويل JSON ↔ Taxpayer ───────────────────────────────────────────────

    private fun taxpayerToJson(t: Taxpayer): JSONObject = JSONObject().apply {
        put("name", t.name)
        put("mother_name", t.motherName)
        put("tax_number", t.taxNumber)
        put("id_number", t.idNumber)
        put("phone", t.phone)
        put("address", t.address)
        put("activity_type", t.activityType)
        put("notes", t.notes)
        put("type", t.type)
        put("status", t.status)
        put("record_number", t.recordNumber)
        put("access_decision_no", t.accessDecisionNo)
        put("decision_date", t.decisionDate)
        put("tax_amount", t.taxAmount)
        put("work_number", t.workNumber)
        put("net_profit", t.netProfit)
        put("property_number", t.propertyNumber)
        put("neighbor_right", t.neighborRight)
        put("neighbor_left", t.neighborLeft)
        put("shop_description", t.shopDescription)
        put("photos", t.photos)
        put("latitude", t.latitude ?: JSONObject.NULL)
        put("longitude", t.longitude ?: JSONObject.NULL)
        put("accuracy", t.accuracy ?: JSONObject.NULL)
        put("captured_at", t.capturedAt ?: JSONObject.NULL)
        put("created_at", t.createdAt)
    }

    private fun jsonToTaxpayer(obj: JSONObject, photosBasePath: String): Taxpayer {
        // تصحيح مسارات الصور لتشير للمسار المحلي الجديد
        val originalPhotos = obj.optString("photos", "")
        val fixedPhotos = if (originalPhotos.isNotBlank()) {
            originalPhotos.split("|").joinToString("|") { path ->
                val fileName = File(path).name
                "$photosBasePath/$fileName"
            }
        } else ""

        return Taxpayer(
            name = obj.optString("name", ""),
            motherName = obj.optString("mother_name", ""),
            taxNumber = obj.optString("tax_number", ""),
            idNumber = obj.optString("id_number", ""),
            phone = obj.optString("phone", ""),
            address = obj.optString("address", ""),
            activityType = obj.optString("activity_type", ""),
            notes = obj.optString("notes", ""),
            type = obj.optString("type", Taxpayer.TYPE_OLD),
            status = obj.optString("status", Taxpayer.STATUS_ACTIVE),
            recordNumber = obj.optInt("record_number", 0),
            accessDecisionNo = obj.optString("access_decision_no", ""),
            decisionDate = obj.optString("decision_date", ""),
            taxAmount = obj.optLong("tax_amount", 0),
            workNumber = obj.optString("work_number", ""),
            netProfit = obj.optLong("net_profit", 0),
            propertyNumber = obj.optString("property_number", ""),
            neighborRight = obj.optString("neighbor_right", ""),
            neighborLeft = obj.optString("neighbor_left", ""),
            shopDescription = obj.optString("shop_description", ""),
            photos = fixedPhotos,
            latitude = if (obj.isNull("latitude")) null else obj.optDouble("latitude"),
            longitude = if (obj.isNull("longitude")) null else obj.optDouble("longitude"),
            accuracy = if (obj.isNull("accuracy")) null else obj.optDouble("accuracy").toFloat(),
            capturedAt = if (obj.isNull("captured_at")) null else obj.optLong("captured_at"),
            createdAt = obj.optLong("created_at", System.currentTimeMillis())
        )
    }

    private fun landmarkToJson(l: Landmark): JSONObject = JSONObject().apply {
        put("name", l.name)
        put("type", l.type)
        put("description", l.description)
        put("area", l.area)
        put("latitude", l.latitude)
        put("longitude", l.longitude)
        put("accuracy", l.accuracy ?: JSONObject.NULL)
        put("is_main_reference", l.isMainReference)
        put("created_at", l.createdAt)
    }

    private fun jsonToLandmark(obj: JSONObject): Landmark = Landmark(
        name = obj.optString("name", ""),
        type = obj.optString("type", Landmark.TYPE_OTHER),
        description = obj.optString("description", ""),
        area = obj.optString("area", ""),
        latitude = obj.optDouble("latitude", 0.0),
        longitude = obj.optDouble("longitude", 0.0),
        accuracy = if (obj.isNull("accuracy")) null else obj.optDouble("accuracy").toFloat(),
        isMainReference = obj.optBoolean("is_main_reference", false),
        createdAt = obj.optLong("created_at", System.currentTimeMillis())
    )

    // ─── معلومات النسخة الاحتياطية ──────────────────────────────────────────

    /**
     * قراءة معلومات ملف نسخة احتياطية دون استيراده
     */
    suspend fun getBackupInfo(uri: Uri): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == DATA_FILE) {
                        val jsonStr = zip.bufferedReader(Charsets.UTF_8).readText()
                        val json = JSONObject(jsonStr)
                        return@withContext BackupInfo(
                            version = json.optInt(VERSION_KEY, 1),
                            createdDate = json.optString("created_date", "غير معروف"),
                            taxpayerCount = json.optInt("taxpayer_count", 0),
                            landmarkCount = json.optInt("landmark_count", 0)
                        )
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read backup info", e)
            null
        }
    }

    data class BackupInfo(
        val version: Int,
        val createdDate: String,
        val taxpayerCount: Int,
        val landmarkCount: Int
    )
}
