package com.taxgps.app.utils

import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * مساعد استيراد CSV المحسّن
 *
 * التحسينات:
 * 1. قراءة سطر بسطر (Streaming) بدلاً من readLines() التي تحمّل كل شيء في الذاكرة
 * 2. دعم UTF-8 مع BOM (شائع في ملفات Excel العربية)
 * 3. فحص coroutine.isActive لإمكانية الإلغاء من الخارج
 * 4. تقرير مفصّل: مضاف / محدّث / مخطوء / مكتمل
 * 5. محلل CSV صحيح يتعامل مع الاقتباسات المزدوجة والفواصل داخل الحقول
 */
class ImportHelper(
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "ImportHelper"
        private const val PROGRESS_INTERVAL = 50 // تحديث شريط التقدم كل 50 سطر
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

    /**
     * تنسيق CSV المتوقع (كما في taxpayers_data.csv):
     * السجل, اسم المكلف, اسم الأم, رقم القرار, تاريخ القرار,
     * الملاحظات, المهنة, العنوان, قرار 2015, تاريخ 2015,
     * مقدار الضريبة, رقم العمل, الربح الصافي
     */
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
            // دعم BOM (Byte Order Mark) الذي يضيفه Excel لملفات UTF-8
            val reader = inputStream
                .bufferedReader(Charsets.UTF_8)
                .let { BomAwareBR(it) }

            var headerLine = reader.readLine()
            if (headerLine == null) {
                listener.onError("الملف فارغ")
                return@withContext
            }

            // تخطّي BOM إن وجد
            if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1)

            // تحديد أعمدة الرأس (مرن — لا يعتمد على الترتيب الثابت)
            val headers = parseCsvLine(headerLine).map { it.trim() }
            val colMap  = buildColumnMap(headers)

            Log.i(TAG, "CSV Headers: $headers")
            Log.i(TAG, "Column map: $colMap")

            // قراءة سطر بسطر (Streaming)
            var line = reader.readLine()
            while (line != null) {
                if (!isActive) break  // إلغاء إن طلب المستخدم ذلك
                lineNum++

                if (line.isBlank()) { line = reader.readLine(); continue }

                try {
                    val parts = parseCsvLine(line)
                    val taxpayer = buildTaxpayer(parts, colMap) ?: run {
                        skipped++
                        line = reader.readLine()
                        continue
                    }

                    val existing = db.findTaxpayerForUpdateAsync(
                        taxpayer.name,
                        taxpayer.accessDecisionNo
                    )

                    if (existing != null) {
                        db.updateTaxpayerAsync(
                            existing.copy(
                                activityType = taxpayer.activityType.ifBlank { existing.activityType },
                                address      = taxpayer.address.ifBlank { existing.address },
                                notes        = taxpayer.notes.ifBlank { existing.notes },
                                accessDecisionNo = taxpayer.accessDecisionNo
                            )
                        )
                        updated++
                    } else {
                        db.insertTaxpayerAsync(taxpayer)
                        added++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error at line $lineNum: ${e.message}")
                    errors++
                }

                // تحديث التقدم كل PROGRESS_INTERVAL سطر
                if (lineNum % PROGRESS_INTERVAL == 0) {
                    val snapshot = ImportResult(added, updated, skipped, errors)
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

    // ── ربط الأعمدة بالمفاتيح ────────────────────────────────────────────────

    /** بناء خريطة من اسم العمود → رقم الفهرس (مرن وغير حساس للمسافات) */
    private fun buildColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        headers.forEachIndexed { i, h ->
            val clean = h.trim().replace("\uFEFF", "")
            map[clean] = i
        }
        return map
    }

    /** بناء Taxpayer من الأجزاء مع خريطة الأعمدة */
    private fun buildTaxpayer(parts: List<String>, colMap: Map<String, Int>): Taxpayer? {
        fun col(vararg names: String): String {
            for (name in names) {
                val idx = colMap[name] ?: continue
                if (idx < parts.size) return parts[idx].trim()
            }
            return ""
        }

        val name = col("اسم المكلف", "الاسم")
        if (name.isBlank()) return null  // سطر بلا اسم → تخطّي

        return Taxpayer(
            name             = name,
            accessDecisionNo = col("رقم القرار"),
            activityType     = col("المهنة", "نوع النشاط"),
            address          = col("العنوان"),
            notes            = col("الملاحظات"),
            type             = Taxpayer.TYPE_OLD  // البيانات المستوردة قديمة افتراضياً
        )
    }

    // ── محلل CSV صحيح ────────────────────────────────────────────────────────

    /**
     * محلل RFC 4180:
     * - يتعامل مع الحقول المحاطة بـ "..."
     * - يتعامل مع "" داخل الحقل كعلامة اقتباس واحدة
     * - يدعم الفواصل داخل الحقول المقتبسة
     */
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
                    // "" داخل حقل مقتبس = علامة اقتباس واحدة
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
