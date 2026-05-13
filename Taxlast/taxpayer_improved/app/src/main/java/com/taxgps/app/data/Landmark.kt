package com.taxgps.app.data

/**
 * نموذج بيانات المعلم المرجعي
 * يستخدم كنقاط مرجعية على الخريطة لتسهيل التنقل في المدينة الصغيرة المكتظة
 *
 * أنواع المعالم:
 * - مسجد: نقطة مرجعية دينية معروفة
 * - مدرسة: نقطة مرجعية تعليمية
 * - تقاطع: تقاطع شوارع رئيسي
 * - دائرة حكومية: مبنى حكومي
 * - سوق: سوق أو مركز تجاري
 * - أخرى: أي معلم آخر
 */
data class Landmark(
    val id: Long = 0,
    val name: String = "",                    // اسم المعلم
    val type: String = TYPE_OTHER,            // نوع المعلم
    val description: String = "",             // وصف المعلم
    val area: String = "",                    // المنطقة / الحي
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float? = null,
    val isMainReference: Boolean = false,     // هل هو معلم رئيسي (يظهر دائماً)
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_MOSQUE = "مسجد"
        const val TYPE_SCHOOL = "مدرسة"
        const val TYPE_INTERSECTION = "تقاطع"
        const val TYPE_GOVERNMENT = "دائرة حكومية"
        const val TYPE_MARKET = "سوق"
        const val TYPE_OTHER = "أخرى"

        val TYPE_LIST = listOf(
            TYPE_MOSQUE,
            TYPE_SCHOOL,
            TYPE_INTERSECTION,
            TYPE_GOVERNMENT,
            TYPE_MARKET,
            TYPE_OTHER
        )

        /** ألوان المعالم حسب النوع - تستخدم في الخريطة */
        fun getTypeColor(type: String): Int = when (type) {
            TYPE_MOSQUE -> android.graphics.Color.parseColor("#4CAF50")
            TYPE_SCHOOL -> android.graphics.Color.parseColor("#2196F3")
            TYPE_INTERSECTION -> android.graphics.Color.parseColor("#FF9800")
            TYPE_GOVERNMENT -> android.graphics.Color.parseColor("#9C27B0")
            TYPE_MARKET -> android.graphics.Color.parseColor("#F44336")
            else -> android.graphics.Color.parseColor("#607D8B")
        }

        /** أيقونة المعلم حسب النوع */
        fun getTypeIcon(type: String): String = when (type) {
            TYPE_MOSQUE -> "🕌"
            TYPE_SCHOOL -> "🏫"
            TYPE_INTERSECTION -> "🔀"
            TYPE_GOVERNMENT -> "🏛️"
            TYPE_MARKET -> "🏪"
            else -> "📍"
        }
    }

    fun hasLocation(): Boolean = latitude != 0.0 && longitude != 0.0
}
