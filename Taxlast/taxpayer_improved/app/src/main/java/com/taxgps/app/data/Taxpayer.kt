package com.taxgps.app.data

/**
 * نموذج بيانات المكلف الضريبي
 * يحتوي على جميع الحقول المطلوبة مع دعم GPS وحالة المزامنة
 *
 * مبني على بنية قاعدة بيانات Access: سجلات_الدخل_المقطوع
 */
data class Taxpayer(
    val id: Long = 0,
    val name: String = "",                    // اسم المكلف
    val motherName: String = "",              // اسم الأم
    val taxNumber: String = "",               // الرقم الضريبي
    val idNumber: String = "",                // رقم الهوية
    val phone: String = "",                   // الهاتف
    val address: String = "",                 // العنوان / المنطقة
    val activityType: String = "",            // المهنة / نوع النشاط
    val notes: String = "",                   // الملاحظات (حديث 2023 / دورة 2020)
    val type: String = TYPE_OLD,              // النوع: قديم / جديد
    val status: String = STATUS_ACTIVE,       // الحالة

    // بيانات القرار المالي (من Access)
    val recordNumber: Int = 0,                // السجل
    val accessDecisionNo: String = "",        // رقم القرار
    val decisionDate: String = "",            // تاريخ القرار
    val taxAmount: Long = 0,                  // مقدار الضريبة (ل.س)
    val workNumber: String = "",              // رقم العمل
    val netProfit: Long = 0,                  // الربح الصافي (ل.س)

    // تعريف المحل
    val propertyNumber: String = "",          // رقم العقار
    val neighborRight: String = "",
    val neighborLeft: String = "",
    val shopDescription: String = "",
    val photos: String = "",                  // مسارات الصور (مفصولة بـ |)

    // بيانات GPS
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val capturedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),

    // مزامنة
    val syncStatus: Int = SYNC_LOCAL,
    val googleDriveId: String = ""
) {
    companion object {
        const val TYPE_OLD = "قديم"
        const val TYPE_NEW = "جديد"

        const val STATUS_ACTIVE = "نشط"
        const val STATUS_INACTIVE = "غير نشط"
        const val STATUS_PENDING = "قيد المراجعة"

        const val SYNC_LOCAL = 0
        const val SYNC_DONE = 1

        // أنواع الملاحظات من Access
        const val NOTE_TYPE_HADIITH = "حديث"   // حديث (سنة)
        const val NOTE_TYPE_DAWRA = "دورة"     // دورة (سنة)

        val STATUS_LIST = listOf(STATUS_ACTIVE, STATUS_INACTIVE, STATUS_PENDING)

        // المناطق المعروفة من قاعدة البيانات
        val KNOWN_AREAS = listOf(
            "القطيلبية", "الصليب", "الدالية", "طوق جبلة",
            "قرى المركز", "سيانو", "عين شقاق", "عرب الملك", "مفرق العقيبة"
        )
    }

    fun hasLocation(): Boolean = latitude != null && longitude != null
    fun isOld(): Boolean = type == TYPE_OLD
    fun isSynced(): Boolean = syncStatus == SYNC_DONE
    fun hasTaxData(): Boolean = taxAmount > 0 || netProfit > 0
    fun getNoteType(): String {
        return when {
            notes.startsWith(NOTE_TYPE_HADIITH) -> NOTE_TYPE_HADIITH
            notes.startsWith(NOTE_TYPE_DAWRA) -> NOTE_TYPE_DAWRA
            else -> ""
        }
    }
    fun getNoteYear(): String {
        val parts = notes.trim().split(" ")
        return if (parts.size >= 2) parts[1] else ""
    }
}
