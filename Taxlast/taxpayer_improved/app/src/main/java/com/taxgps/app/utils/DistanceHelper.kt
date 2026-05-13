package com.taxgps.app.utils

import com.taxgps.app.data.Landmark
import com.taxgps.app.data.Taxpayer
import kotlin.math.*

/**
 * مساعد حساب المسافات والعلاقات المكانية
 *
 * مصمم لمدينة صغيرة مكتظة حيث المسافات بالأمتار
 * يستخدم صيغة Haversine لحساب المسافات الدقيقة
 *
 * الميزات:
 * - حساب المسافة بين نقطتين بالأمتار
 * - إيجاد أقرب المكلفين لنقطة معينة
 * - إيجاد أقرب معلم مرجعي
 * - حساب المسافات بين كل المحلات
 * - تجميع المحلات حسب القرب (clustering)
 * - تحديد المحلات ضمن نطاق معين
 */
object DistanceHelper {

    private const val EARTH_RADIUS_METERS = 6_371_000.0  // نصف قطر الأرض بالأمتار

    // ─── حساب المسافة ────────────────────────────────────────────────────────

    /**
     * حساب المسافة بين نقطتين بالأمتار باستخدام صيغة Haversine
     * دقيقة جداً للمسافات القصيرة (مناسبة لمدينة صغيرة)
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * حساب المسافة بين مكلفَين
     */
    fun distanceBetweenTaxpayers(t1: Taxpayer, t2: Taxpayer): Double? {
        if (!t1.hasLocation() || !t2.hasLocation()) return null
        return calculateDistance(t1.latitude!!, t1.longitude!!, t2.latitude!!, t2.longitude!!)
    }

    /**
     * حساب المسافة بين مكلف ومعلم مرجعي
     */
    fun distanceToLandmark(taxpayer: Taxpayer, landmark: Landmark): Double? {
        if (!taxpayer.hasLocation() || !landmark.hasLocation()) return null
        return calculateDistance(taxpayer.latitude!!, taxpayer.longitude!!, landmark.latitude, landmark.longitude)
    }

    /**
     * حساب المسافة بين معلمَين مرجعيَّين
     */
    fun distanceBetweenLandmarks(l1: Landmark, l2: Landmark): Double {
        return calculateDistance(l1.latitude, l1.longitude, l2.latitude, l2.longitude)
    }

    // ─── البحث عن الأقرب ─────────────────────────────────────────────────────

    /**
     * إيجاد أقرب N مكلفين لنقطة معينة
     */
    fun findNearestTaxpayers(
        lat: Double, lon: Double,
        taxpayers: List<Taxpayer>,
        maxCount: Int = 5,
        maxDistanceMeters: Double = Double.MAX_VALUE
    ): List<TaxpayerDistance> {
        return taxpayers
            .filter { it.hasLocation() }
            .map { t ->
                TaxpayerDistance(
                    taxpayer = t,
                    distanceMeters = calculateDistance(lat, lon, t.latitude!!, t.longitude!!)
                )
            }
            .filter { it.distanceMeters <= maxDistanceMeters }
            .sortedBy { it.distanceMeters }
            .take(maxCount)
    }

    /**
     * إيجاد أقرب معلم مرجعي لمكلف
     */
    fun findNearestLandmark(taxpayer: Taxpayer, landmarks: List<Landmark>): LandmarkDistance? {
        if (!taxpayer.hasLocation()) return null
        return landmarks
            .filter { it.hasLocation() }
            .map { lm ->
                LandmarkDistance(
                    landmark = lm,
                    distanceMeters = calculateDistance(
                        taxpayer.latitude!!, taxpayer.longitude!!,
                        lm.latitude, lm.longitude
                    )
                )
            }
            .minByOrNull { it.distanceMeters }
    }

    /**
     * إيجاد أقرب N معالم لنقطة معينة
     */
    fun findNearestLandmarks(
        lat: Double, lon: Double,
        landmarks: List<Landmark>,
        maxCount: Int = 3
    ): List<LandmarkDistance> {
        return landmarks
            .filter { it.hasLocation() }
            .map { lm ->
                LandmarkDistance(
                    landmark = lm,
                    distanceMeters = calculateDistance(lat, lon, lm.latitude, lm.longitude)
                )
            }
            .sortedBy { it.distanceMeters }
            .take(maxCount)
    }

    // ─── المكلفون ضمن نطاق ──────────────────────────────────────────────────

    /**
     * إيجاد كل المكلفين ضمن نطاق معين (بالأمتار) من نقطة
     */
    fun findTaxpayersWithinRadius(
        lat: Double, lon: Double,
        taxpayers: List<Taxpayer>,
        radiusMeters: Double
    ): List<TaxpayerDistance> {
        return taxpayers
            .filter { it.hasLocation() }
            .map { t ->
                TaxpayerDistance(
                    taxpayer = t,
                    distanceMeters = calculateDistance(lat, lon, t.latitude!!, t.longitude!!)
                )
            }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
    }

    // ─── حساب المسافات بين كل المحلات ───────────────────────────────────────

    /**
     * حساب مصفوفة المسافات بين كل المكلفين الذين لديهم موقع
     * مفيد لرسم الخطوط بينهم على الخريطة
     *
     * ملاحظة: يُرجِع فقط الأزواج التي مسافتها أقل من maxDistance
     * لتجنب رسم خطوط كثيرة في المدينة المكتظة
     */
    fun calculatePairwiseDistances(
        taxpayers: List<Taxpayer>,
        maxDistanceMeters: Double = 200.0  // 200 متر كحد أقصى للربط
    ): List<TaxpayerPairDistance> {
        val located = taxpayers.filter { it.hasLocation() }
        val pairs = mutableListOf<TaxpayerPairDistance>()

        for (i in located.indices) {
            for (j in i + 1 until located.size) {
                val dist = calculateDistance(
                    located[i].latitude!!, located[i].longitude!!,
                    located[j].latitude!!, located[j].longitude!!
                )
                if (dist <= maxDistanceMeters) {
                    pairs.add(TaxpayerPairDistance(located[i], located[j], dist))
                }
            }
        }
        return pairs.sortedBy { it.distanceMeters }
    }

    /**
     * إيجاد الجيران المباشرين لمكلف (أقرب محلات في كل اتجاه)
     * مفيد في المدينة المكتظة لمعرفة المحلات المجاورة
     */
    fun findDirectNeighbors(
        taxpayer: Taxpayer,
        allTaxpayers: List<Taxpayer>,
        maxNeighbors: Int = 4
    ): List<TaxpayerDistance> {
        if (!taxpayer.hasLocation()) return emptyList()
        return allTaxpayers
            .filter { it.hasLocation() && it.id != taxpayer.id }
            .map { t ->
                TaxpayerDistance(
                    taxpayer = t,
                    distanceMeters = calculateDistance(
                        taxpayer.latitude!!, taxpayer.longitude!!,
                        t.latitude!!, t.longitude!!
                    )
                )
            }
            .sortedBy { it.distanceMeters }
            .take(maxNeighbors)
    }

    // ─── تجميع المحلات (Clustering) ─────────────────────────────────────────

    /**
     * تجميع المحلات القريبة من بعضها في مجموعات
     * مفيد لتقليل الازدحام على الخريطة
     *
     * @param clusterRadiusMeters: المسافة القصوى لاعتبار محلين في نفس المجموعة
     */
    fun clusterTaxpayers(
        taxpayers: List<Taxpayer>,
        clusterRadiusMeters: Double = 50.0
    ): List<TaxpayerCluster> {
        val located = taxpayers.filter { it.hasLocation() }.toMutableList()
        val clusters = mutableListOf<TaxpayerCluster>()

        while (located.isNotEmpty()) {
            val seed = located.removeAt(0)
            val cluster = mutableListOf(seed)

            val iterator = located.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val dist = calculateDistance(
                    seed.latitude!!, seed.longitude!!,
                    candidate.latitude!!, candidate.longitude!!
                )
                if (dist <= clusterRadiusMeters) {
                    cluster.add(candidate)
                    iterator.remove()
                }
            }

            // حساب مركز المجموعة
            val centerLat = cluster.sumOf { it.latitude!! } / cluster.size
            val centerLon = cluster.sumOf { it.longitude!! } / cluster.size

            clusters.add(
                TaxpayerCluster(
                    taxpayers = cluster,
                    centerLatitude = centerLat,
                    centerLongitude = centerLon,
                    count = cluster.size
                )
            )
        }
        return clusters
    }

    // ─── تنسيق المسافة ──────────────────────────────────────────────────────

    /**
     * تنسيق المسافة بطريقة قابلة للقراءة
     * في المدينة الصغيرة: عادة بالأمتار
     */
    fun formatDistance(meters: Double): String {
        return when {
            meters < 1 -> "أقل من متر"
            meters < 1000 -> "${meters.roundToInt()} م"
            else -> String.format("%.1f كم", meters / 1000)
        }
    }

    /**
     * تنسيق المسافة مع وصف نسبي
     */
    fun formatDistanceWithLabel(meters: Double): String {
        val dist = formatDistance(meters)
        val label = when {
            meters < 10 -> "ملاصق"
            meters < 30 -> "مجاور"
            meters < 100 -> "قريب"
            meters < 300 -> "متوسط"
            meters < 500 -> "بعيد نسبياً"
            else -> "بعيد"
        }
        return "$dist ($label)"
    }

    /**
     * حساب الاتجاه بين نقطتين (البوصلة)
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    /**
     * تحويل الاتجاه الرقمي إلى اتجاه نصي
     */
    fun bearingToDirection(bearing: Double): String {
        return when {
            bearing < 22.5 || bearing >= 337.5 -> "شمال"
            bearing < 67.5 -> "شمال شرق"
            bearing < 112.5 -> "شرق"
            bearing < 157.5 -> "جنوب شرق"
            bearing < 202.5 -> "جنوب"
            bearing < 247.5 -> "جنوب غرب"
            bearing < 292.5 -> "غرب"
            else -> "شمال غرب"
        }
    }

    // ─── حساب مساحة المنطقة ──────────────────────────────────────────────────

    /**
     * حساب المربع المحيط (Bounding Box) لمجموعة نقاط
     */
    fun calculateBoundingBox(points: List<Pair<Double, Double>>): BoundingBox? {
        if (points.isEmpty()) return null
        return BoundingBox(
            minLat = points.minOf { it.first },
            maxLat = points.maxOf { it.first },
            minLon = points.minOf { it.second },
            maxLon = points.maxOf { it.second }
        )
    }

    /**
     * حساب مركز مجموعة نقاط
     */
    fun calculateCenter(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.isEmpty()) return null
        return Pair(
            points.sumOf { it.first } / points.size,
            points.sumOf { it.second } / points.size
        )
    }

    // ─── تقريب ──────────────────────────────────────────────────────────────

    private fun Double.roundToInt(): Int = kotlin.math.roundToInt(this)
}

// ═══════════════════════════════════════════════════════════════════════════════
// ─── نماذج البيانات المساعدة ─────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════════════════

/** مكلف مع المسافة من نقطة مرجعية */
data class TaxpayerDistance(
    val taxpayer: Taxpayer,
    val distanceMeters: Double
) {
    fun formattedDistance(): String = DistanceHelper.formatDistance(distanceMeters)
    fun formattedDistanceWithLabel(): String = DistanceHelper.formatDistanceWithLabel(distanceMeters)
}

/** معلم مرجعي مع المسافة */
data class LandmarkDistance(
    val landmark: Landmark,
    val distanceMeters: Double
) {
    fun formattedDistance(): String = DistanceHelper.formatDistance(distanceMeters)
}

/** زوج مكلفين مع المسافة بينهما */
data class TaxpayerPairDistance(
    val taxpayer1: Taxpayer,
    val taxpayer2: Taxpayer,
    val distanceMeters: Double
) {
    fun formattedDistance(): String = DistanceHelper.formatDistance(distanceMeters)
}

/** مجموعة محلات متقاربة */
data class TaxpayerCluster(
    val taxpayers: List<Taxpayer>,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val count: Int
)

/** المربع المحيط */
data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    fun center(): Pair<Double, Double> = Pair(
        (minLat + maxLat) / 2,
        (minLon + maxLon) / 2
    )

    fun widthMeters(): Double = DistanceHelper.calculateDistance(
        minLat, minLon, minLat, maxLon
    )

    fun heightMeters(): Double = DistanceHelper.calculateDistance(
        minLat, minLon, maxLat, minLon
    )
}

private fun kotlin.math.roundToInt(value: Double): Int = value.roundToInt()
