package com.taxgps.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * مستورد ملفات Microsoft Access (.accdb / .mdb)
 *
 * يقرأ البيانات مباشرة من ملف Access عن طريق تحليل البيانات الثنائية
 * واستخراج السجلات النصية المخزنة بتنسيق UTF-16LE
 *
 * بنية الجدول المتوقعة (سجلات_الدخل_المقطوع):
 * السجل | اسم المكلف | اسم الأم | رقم القرار | تاريخ القرار |
 * الملاحظات | المهنة | العنوان | مقدار الضريبة | رقم العمل | الربح الصافي
 */
class AccessDbImportHelper(
    private val context: Context,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "AccessDbImport"
        private const val BATCH_SIZE = 100        // حجم الدفعة للإدخال
        private const val PROGRESS_INTERVAL = 50  // تحديث التقدم كل N سجل
    }

    // ── واجهة التقدم ─────────────────────────────────────────────────────────

    interface ImportListener {
        fun onProgress(current: Int, total: Int, message: String)
        fun onFinished(result: ImportResult)
        fun onError(error: String)
    }

    data class ImportResult(
        val added: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
        val errors: Int = 0
    ) {
        val total get() = added + updated + skipped + errors
    }

    // ── الاستيراد الرئيسي ─────────────────────────────────────────────────────

    /**
     * استيراد ملف Access (.accdb) من URI
     * يقرأ الملف كاملاً ثم يستخرج السجلات بتحليل البيانات الثنائية
     */
    suspend fun importFromUri(
        uri: Uri,
        listener: ImportListener,
        clearExisting: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) { listener.onError("لا يمكن فتح الملف") }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                listener.onProgress(0, 0, "جاري قراءة الملف...")
            }

            val data = inputStream.use { it.readBytes() }
            Log.i(TAG, "File loaded: ${data.size} bytes")

            if (clearExisting) {
                db.deleteAllAsync()
                Log.i(TAG, "Existing data cleared")
            }

            // استخراج السجلات
            val records = extractRecords(data)
            Log.i(TAG, "Extracted ${records.size} records")

            if (records.isEmpty()) {
                withContext(Dispatchers.Main) {
                    listener.onError("لم يتم العثور على سجلات في الملف.\nتأكد أن الملف بصيغة .accdb صحيحة.")
                }
                return@withContext
            }

            // إدخال السجلات
            var added = 0
            var updated = 0
            var skipped = 0
            var errors = 0
            val batch = mutableListOf<Taxpayer>()

            for ((index, record) in records.withIndex()) {
                if (!isActive) break

                try {
                    val taxpayer = record.toTaxpayer() ?: run {
                        skipped++
                        continue
                    }

                    // فحص التكرار
                    val existing = db.findByNameAndRecordAsync(taxpayer.name, taxpayer.recordNumber)
                    if (existing != null) {
                        // تحديث السجل الموجود
                        db.updateTaxpayerAsync(existing.copy(
                            motherName      = taxpayer.motherName.ifBlank { existing.motherName },
                            accessDecisionNo = taxpayer.accessDecisionNo.ifBlank { existing.accessDecisionNo },
                            decisionDate    = taxpayer.decisionDate.ifBlank { existing.decisionDate },
                            taxAmount       = if (taxpayer.taxAmount > 0) taxpayer.taxAmount else existing.taxAmount,
                            workNumber      = taxpayer.workNumber.ifBlank { existing.workNumber },
                            netProfit       = if (taxpayer.netProfit > 0) taxpayer.netProfit else existing.netProfit,
                            activityType    = taxpayer.activityType.ifBlank { existing.activityType },
                            address         = taxpayer.address.ifBlank { existing.address },
                            notes           = taxpayer.notes.ifBlank { existing.notes }
                        ))
                        updated++
                    } else {
                        batch.add(taxpayer)
                        if (batch.size >= BATCH_SIZE) {
                            db.insertBatchAsync(batch)
                            added += batch.size
                            batch.clear()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error at record $index: ${e.message}")
                    errors++
                }

                // تحديث التقدم
                if (index % PROGRESS_INTERVAL == 0) {
                    withContext(Dispatchers.Main) {
                        listener.onProgress(index + 1, records.size,
                            "جاري الاستيراد: ${index + 1} من ${records.size}")
                    }
                }
            }

            // إدخال آخر دفعة
            if (batch.isNotEmpty()) {
                db.insertBatchAsync(batch)
                added += batch.size
            }

            withContext(Dispatchers.Main) {
                listener.onFinished(ImportResult(added, updated, skipped, errors))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            withContext(Dispatchers.Main) {
                listener.onError("خطأ أثناء الاستيراد: ${e.message}")
            }
        }
    }

    // ── استخراج السجلات من البيانات الثنائية ──────────────────────────────────

    /**
     * تحليل ملف Access واستخراج سجلات المكلفين
     *
     * استراتيجية محسّنة v2: قراءة متعددة المحاولات
     * 1. محاولة قراءة كـ UTF-16LE (التنسيق الداخلي لـ Access)
     * 2. محاولة قراءة كـ UTF-8
     * 3. استخدام أنماط regex أوسع لالتقاط أكبر عدد ممكن
     * 4. عدم فرض تطابق العنوان مع قائمة محددة
     */
    private fun extractRecords(data: ByteArray): List<AccessRecord> {
        val records = mutableListOf<AccessRecord>()

        // محاولة 1: UTF-16LE
        try {
            val text16 = String(data, Charset.forName("UTF-16LE"))
            extractFromText(text16, records)
        } catch (e: Exception) {
            Log.w(TAG, "UTF-16LE failed: ${e.message}")
        }

        // محاولة 2: UTF-8
        if (records.size < 50) {  // إذا لم نجد كثيراً
            try {
                val text8 = String(data, Charsets.UTF_8)
                extractFromText(text8, records)
            } catch (e: Exception) {
                Log.w(TAG, "UTF-8 failed: ${e.message}")
            }
        }

        // محاولة 3: Windows-1256 (ترميز عربي شائع)
        if (records.size < 50) {
            try {
                val textAr = String(data, Charset.forName("windows-1256"))
                extractFromText(textAr, records)
            } catch (e: Exception) {
                Log.w(TAG, "Windows-1256 failed: ${e.message}")
            }
        }

        // إزالة التكرارات
        val unique = records
            .groupBy { "${it.recordNumber}_${it.name.take(10)}" }
            .map { (_, group) -> group.maxByOrNull { it.completeness() } ?: group.first() }
            .sortedBy { it.recordNumber }

        Log.i(TAG, "Total unique records: ${unique.size}")
        return unique
    }

    /**
     * استخراج السجلات من نص — أنماط regex موسّعة
     */
    private fun extractFromText(text: String, records: MutableList<AccessRecord>) {
        // نمط 1: اسم عربي + رقم + تاريخ + (حديث|دورة) + مهنة + عنوان
        val pattern1 = Regex(
            """([\u0600-\u06FF\s]{4,50}?)(\d{1,5})\s{0,5}(\d{1,2}[/\\]\d{1,2}[/\\]\d{4})(\d{2,15})(حديث|دورة)\s+(\d{4})([\u0600-\u06FF\s\-]{2,40}?)(القطيلبية|الصليب|الدالية|طوق جبلة|قرى المركز|قرى مركز|قر المركز|سيانو|عين شقاق|عرب الملك|مفرق العقيبة)"""
        )

        for (match in pattern1.findAll(text)) {
            try {
                val name = match.groupValues[1].trim()
                val recordNum = match.groupValues[2].trim().toIntOrNull() ?: 0
                val date = match.groupValues[3].trim()
                val numbers = match.groupValues[4].trim()
                val noteType = match.groupValues[5].trim()
                val noteYear = match.groupValues[6].trim()
                val profession = match.groupValues[7].trim()
                val address = match.groupValues[8].trim()
                val parsed = parseNumbers(numbers)

                if (name.length >= 4 && recordNum > 0) {
                    records.add(AccessRecord(recordNum, name, "", "", date,
                        "$noteType $noteYear", profession, address,
                        parsed.taxAmount, parsed.workNumber, parsed.netProfit))
                }
            } catch (e: Exception) { /* skip */ }
        }

        // نمط 2: أبسط — اسم عربي + رقم سجل + تاريخ (بدون فرض عنوان)
        val pattern2 = Regex(
            """([\u0600-\u06FF][\u0600-\u06FF\s]{3,45}?)\s+(\d{1,5})\s+(\d{1,2}[/\\]\d{1,2}[/\\]\d{4})\s+([\u0600-\u06FF\s]{2,30})"""
        )

        for (match in pattern2.findAll(text)) {
            try {
                val name = match.groupValues[1].trim()
                val recordNum = match.groupValues[2].trim().toIntOrNull() ?: 0
                val date = match.groupValues[3].trim()
                val extra = match.groupValues[4].trim()

                if (name.length >= 4 && recordNum > 0) {
                    // تجنب التكرار
                    val exists = records.any { it.recordNumber == recordNum && it.name.take(8) == name.take(8) }
                    if (!exists) {
                        records.add(AccessRecord(recordNum, name, "", "", date,
                            "", extra, "", 0, "", 0))
                    }
                }
            } catch (e: Exception) { /* skip */ }
        }

        // نمط 3: أسماء عربية مع أرقام (أوسع)
        val pattern3 = Regex(
            """([\u0600-\u06FF][\u0600-\u06FF\s]{5,40})\s+(\d{1,5})\s+(\d{1,2}[/\\]\d{1,2}[/\\]\d{4})"""
        )

        for (match in pattern3.findAll(text)) {
            try {
                val name = match.groupValues[1].trim()
                val recordNum = match.groupValues[2].trim().toIntOrNull() ?: 0
                val date = match.groupValues[3].trim()

                if (name.length >= 5 && recordNum > 0 && !name.contains("جدول") && !name.contains("سجلات")) {
                    val exists = records.any { it.recordNumber == recordNum && it.name.take(8) == name.take(8) }
                    if (!exists) {
                        records.add(AccessRecord(recordNum, name, "", "", date,
                            "", "", "", 0, "", 0))
                    }
                }
            } catch (e: Exception) { /* skip */ }
        }

        Log.i(TAG, "extractFromText: found ${records.size} records total")
    }

    /**
     * تحليل سلسلة الأرقام المتصلة إلى: مقدار الضريبة + رقم العمل + الربح الصافي
     *
     * من البيانات المحللة:
     * "1584009920000992000" → tax≈1584, numbers≈00992, profit≈992000
     */
    private fun parseNumbers(numbers: String): ParsedNumbers {
        if (numbers.length < 6) return ParsedNumbers(0, "", 0)

        return try {
            // الأرقام عادة مرتبة: [مقدار_الضريبة][رقم_العمل][الربح_الصافي]
            // مقدار الضريبة: 3-5 أرقام
            // رقم العمل: 4-7 أرقام (غالباً يبدأ بـ 00)
            // الربح الصافي: 5-7 أرقام

            when {
                numbers.length >= 15 -> {
                    // نمط طويل: أول 3-4 = ضريبة، أوسط = عمل، آخر 6 = ربح
                    val profit = numbers.takeLast(6).toLongOrNull() ?: 0
                    val tax = numbers.take(4).toLongOrNull() ?: 0
                    val work = numbers.drop(4).dropLast(6)
                    ParsedNumbers(tax, work, profit)
                }
                numbers.length >= 10 -> {
                    val profit = numbers.takeLast(6).toLongOrNull() ?: 0
                    val tax = numbers.take(3).toLongOrNull() ?: 0
                    val work = numbers.drop(3).dropLast(6)
                    ParsedNumbers(tax, work, profit)
                }
                else -> {
                    ParsedNumbers(numbers.toLongOrNull() ?: 0, "", 0)
                }
            }
        } catch (e: Exception) {
            ParsedNumbers(0, "", 0)
        }
    }

    // ── نماذج مساعدة ─────────────────────────────────────────────────────────

    private data class ParsedNumbers(
        val taxAmount: Long,
        val workNumber: String,
        val netProfit: Long
    )

    data class AccessRecord(
        val recordNumber: Int,
        val name: String,
        val motherName: String,
        val decisionNo: String,
        val decisionDate: String,
        val notes: String,
        val profession: String,
        val address: String,
        val taxAmount: Long,
        val workNumber: String,
        val netProfit: Long
    ) {
        /** درجة اكتمال السجل (للترجيح عند التكرار) */
        fun completeness(): Int {
            var score = 0
            if (name.isNotBlank()) score += 2
            if (motherName.isNotBlank()) score += 1
            if (decisionNo.isNotBlank()) score += 1
            if (decisionDate.isNotBlank()) score += 1
            if (notes.isNotBlank()) score += 1
            if (profession.isNotBlank()) score += 1
            if (address.isNotBlank()) score += 1
            if (taxAmount > 0) score += 1
            if (netProfit > 0) score += 1
            return score
        }

        fun toTaxpayer(): Taxpayer? {
            if (name.isBlank() || name.length < 3) return null

            // تحديد النوع بناءً على الملاحظات
            val type = when {
                notes.contains("حديث") -> Taxpayer.TYPE_NEW
                else -> Taxpayer.TYPE_OLD
            }

            return Taxpayer(
                recordNumber     = recordNumber,
                name             = name,
                motherName       = motherName,
                accessDecisionNo = decisionNo,
                decisionDate     = decisionDate,
                notes            = notes,
                activityType     = profession,
                address          = address,
                taxAmount        = taxAmount,
                workNumber       = workNumber,
                netProfit        = netProfit,
                type             = type,
                status           = Taxpayer.STATUS_ACTIVE
            )
        }
    }

    // ── استيراد CSV كبديل ─────────────────────────────────────────────────────

    /**
     * استيراد من ملف CSV مُصدَّر من Access
     * (بديل في حال تصدير المستخدم البيانات يدوياً)
     */
    suspend fun importFromCsv(
        uri: Uri,
        listener: ImportListener,
        clearExisting: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) { listener.onError("لا يمكن فتح الملف") }
                return@withContext
            }

            if (clearExisting) db.deleteAllAsync()

            val importHelper = ImportHelper(db)
            importHelper.importFromCsv(inputStream, object : ImportHelper.ImportListener {
                override fun onProgress(current: Int, estimated: Int, added: Int, updated: Int) {
                    listener.onProgress(current, estimated, "جاري الاستيراد: $current سجل")
                }
                override fun onFinished(result: ImportHelper.ImportResult) {
                    listener.onFinished(ImportResult(result.added, result.updated, result.skipped, result.errors))
                }
                override fun onError(error: String) {
                    listener.onError(error)
                }
            })
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError("خطأ: ${e.message}")
            }
        }
    }
}
