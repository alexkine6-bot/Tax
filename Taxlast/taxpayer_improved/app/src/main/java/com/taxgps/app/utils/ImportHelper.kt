package com.taxgps.app.utils

import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream

/**
 * مساعد استيراد CSV المحسّن
 *
 * يدعم بنية ملف Access المُصدَّر (سجلات_الدخل_المقطوع):
 * السجل, اسم المكلف, اسم الأم, رقم القرار, تاريخ القرار,
 * الملاحظات, المهنة, العنوان, مقدار الضريبة, رقم العمل, الربح الصافي
 *
 * التحسينات:
 * 1. قراءة سطر بسطر (Streaming)
 * 2. دعم UTF-8 مع BOM
 * 3. فحص coroutine.isActive للإلغاء
 * 4. محلل CSV صحيح (RFC 4180)
 * 5. ربط الأعمدة بالاسم (مرن)
 * 6. دعم جميع الأعمدة الجديدة من Access
 */
class ImportHelper(
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "ImportHelper"
        private const val PROGRESS_INTERVAL = 50
    }

    // ── واجهة متابعة التقدم ───────────────────────────────────────────────────

    interface ImportListener {
        fun onProgress(current: Int, estimated: Int, added: Int, updated: Int)
        fun onFinished(result: ImportResult)
        fun onError(error: String)
    }

    data class ImportResult(
        val added:   Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
        val errors:  Int = 0
    ) {
        val total get() = added + updated + skipped + errors
    }

    // ── الاستيراد الرئيسي ─────────────────────────────────────────────────────

    suspend fun importFromCsv(
        inputStream: InputStream,
        listener: ImportListener
    ) = withContext(Dispatchers.IO) {

        var added   = 0
        var updated = 0
        var skipped = 0
        var errors  = 0
        var lineNum = 0

        try {
            val reader = inputStream
                .bufferedReader(Charsets.UTF_8)
                .let { BomAwareBR(it) }

            var headerLine = reader.readLine()
            if (headerLine == null) {
                withContext(Dispatchers.Main) { listener.onError("الملف فارغ") }
                return@withContext
            }

            // تخطّي BOM
            if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1)

            // تحديد الأعمدة (مرن — بالاسم لا بالترتيب)
            val headers = parseCsvLine(headerLine).map { it.trim() }
            val colMap  = buildColumnMap(headers)

            Log.i(TAG, "CSV Headers: $headers")
            Log.i(TAG, "Column map: $colMap")

            // قراءة سطر بسطر
            var line = reader.readLine()
            while (line != null) {
                if (!isActive) break
                lineNum++

                if (line.isBlank()) { line = reader.readLine(); continue }

                try {
                    val parts = parseCsvLine(line)
                    val taxpayer = buildTaxpayer(parts, colMap) ?: run {
                        skipped++
                        line = reader.readLine()
                        continue
                    }

                    // فحص التكرار: بالاسم + رقم القرار أو رقم السجل
                    val existing = findExisting(taxpayer)

                    if (existing != null) {
                        db.updateTaxpayerAsync(mergeRecords(existing, taxpayer))
                        updated++
                    } else {
                        db.insertTaxpayerAsync(taxpayer)
                        added++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error at line $lineNum: ${e.message}")
                    errors++
                }

                if (lineNum % PROGRESS_INTERVAL == 0) {
                    withContext(Dispatchers.Main) {
                        listener.onProgress(lineNum, lineNum + 100, added, updated)
                    }
                }

                line = reader.readLine()
            }

            withContext(Dispatchers.Main) {
                listener.onFinished(ImportResult(added, updated, skipped, errors))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            withContext(Dispatchers.Main) {
                listener.onError("خطأ أثناء الاستيراد في السطر $lineNum: ${e.message}")
            }
        }
    }

    // ── البحث عن سجل مكرر ────────────────────────────────────────────────────

    private suspend fun findExisting(taxpayer: Taxpayer): Taxpayer? {
        // أولاً: البحث بالاسم + رقم السجل
        if (taxpayer.recordNumber > 0) {
            val byRecord = db.findByNameAndRecordAsync(taxpayer.name, taxpayer.recordNumber)
            if (byRecord != null) return byRecord
        }
        // ثانياً: البحث بالاسم + رقم القرار
        if (taxpayer.accessDecisionNo.isNotBlank()) {
            val byDecision = db.findTaxpayerForUpdateAsync(taxpayer.name, taxpayer.accessDecisionNo)
            if (byDecision != null) return byDecision
        }
        return null
    }

    /** دمج بيانات السجل الجديد مع الموجود (الحقل الجديد يأخذ أولوية إن لم يكن فارغاً) */
    private fun mergeRecords(existing: Taxpayer, incoming: Taxpayer): Taxpayer {
        return existing.copy(
            motherName       = incoming.motherName.ifBlank { existing.motherName },
            activityType     = incoming.activityType.ifBlank { existing.activityType },
            address          = incoming.address.ifBlank { existing.address },
            notes            = incoming.notes.ifBlank { existing.notes },
            accessDecisionNo = incoming.accessDecisionNo.ifBlank { existing.accessDecisionNo },
            decisionDate     = incoming.decisionDate.ifBlank { existing.decisionDate },
            taxAmount        = if (incoming.taxAmount > 0) incoming.taxAmount else existing.taxAmount,
            workNumber       = incoming.workNumber.ifBlank { existing.workNumber },
            netProfit        = if (incoming.netProfit > 0) incoming.netProfit else existing.netProfit,
            recordNumber     = if (incoming.recordNumber > 0) incoming.recordNumber else existing.recordNumber
        )
    }

    // ── ربط الأعمدة بالمفاتيح ────────────────────────────────────────────────

    private fun buildColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        headers.forEachIndexed { i, h ->
            val clean = h.trim().replace("\uFEFF", "")
            map[clean] = i
        }
        return map
    }

    /**
     * بناء Taxpayer من أجزاء CSV مع خريطة الأعمدة
     * يدعم أسماء أعمدة متعددة لكل حقل (مرونة)
     */
    private fun buildTaxpayer(parts: List<String>, colMap: Map<String, Int>): Taxpayer? {
        fun col(vararg names: String): String {
            for (name in names) {
                val idx = colMap[name] ?: continue
                if (idx < parts.size) return parts[idx].trim()
            }
            return ""
        }

        fun colLong(vararg names: String): Long {
            val value = col(*names)
            return value.replace(",", "").replace(".", "").toLongOrNull() ?: 0
        }

        fun colInt(vararg names: String): Int {
            val value = col(*names)
            return value.replace(",", "").toIntOrNull() ?: 0
        }

        val name = col("اسم المكلف", "الاسم", "Name")
        if (name.isBlank()) return null

        return Taxpayer(
            recordNumber     = colInt("السجل", "رقم السجل", "Record"),
            name             = name,
            motherName       = col("اسم الأم", "اسم الام", "Mother"),
            accessDecisionNo = col("رقم القرار", "القرار", "Decision"),
            decisionDate     = col("تاريخ القرار", "التاريخ", "Date"),
            notes            = col("الملاحظات", "ملاحظات", "Notes"),
            activityType     = col("المهنة", "نوع النشاط", "Activity"),
            address          = col("العنوان", "المنطقة", "Address"),
            taxAmount        = colLong("مقدار الضريبة", "الضريبة", "Tax"),
            workNumber       = col("رقم العمل", "العمل", "Work"),
            netProfit        = colLong("الربح الصافي", "الربح", "Profit"),
            taxNumber        = col("الرقم الضريبي", "رقم ضريبي"),
            phone            = col("الهاتف", "رقم الهاتف", "Phone"),
            type             = when {
                col("الملاحظات", "ملاحظات").contains("حديث") -> Taxpayer.TYPE_NEW
                col("النوع", "Type") == Taxpayer.TYPE_NEW -> Taxpayer.TYPE_NEW
                else -> Taxpayer.TYPE_OLD
            }
        )
    }

    // ── محلل CSV صحيح (RFC 4180) ────────────────────────────────────────────

    private fun parseCsvLine(line: String): List<String> {
        val result    = mutableListOf<String>()
        val current   = StringBuilder()
        var inQuotes  = false
        var i         = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> inQuotes = true
                ch == '"' && inQuotes  -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    /** قارئ يتجاهل BOM تلقائياً */
    private class BomAwareBR(private val inner: BufferedReader) : BufferedReader(inner) {
        override fun readLine(): String? = inner.readLine()
    }
}
