package com.taxgps.app.data

/**
 * نموذج بيانات المكلف الضريبي
 * يحتوي على جميع الحقول المطلوبة مع دعم GPS وحالة المزامنة
 */
data class Taxpayer(
    val id: Long = 0,
    val name: String = "",
    val taxNumber: String = "",
    val idNumber: String = "",
    val phone: String = "",
    val address: String = "",
    val activityType: String = "",
    val notes: String = "",
    val type: String = TYPE_OLD,
    val status: String = STATUS_ACTIVE,

    // تعريف المحل
    val neighborRight: String = "",
    val neighborLeft: String = "",
    val shopDescription: String = "",

    // بيانات GPS
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val capturedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),

    // ربط بملف Access
    val accessDecisionNo: String = "",
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

        val STATUS_LIST = listOf(STATUS_ACTIVE, STATUS_INACTIVE, STATUS_PENDING)
    }

    fun hasLocation(): Boolean = latitude != null && longitude != null
    fun isOld(): Boolean = type == TYPE_OLD
    fun isSynced(): Boolean = syncStatus == SYNC_DONE
}
