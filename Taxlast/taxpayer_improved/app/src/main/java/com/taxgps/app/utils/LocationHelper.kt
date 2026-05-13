package com.taxgps.app.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentSender
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * مساعد GPS المحسّن
 *
 * التحسينات:
 * 1. Weighted Average: القراءات الأدق لها وزن أعلى (1/accuracy²)
 * 2. Timeout: تحذير بعد 60 ثانية إن لم تصل قراءة جيدة
 * 3. أفضل قراءة منفردة كخيار احتياطي (bestSingleReading)
 * 4. Auto-stop عند تحقيق الدقة المطلوبة
 * 5. رفض القراءات الأسوأ من 50 متر
 */
class LocationHelper(private val context: Context) {

    companion object {
        private const val TAG = "LocationHelper"

        const val MAX_SAMPLES            = 10
        const val IDEAL_ACCURACY_METERS  = 15f   // دقة مثالية لمحل تجاري
        const val GOOD_ACCURACY_METERS   = 25f   // دقة جيدة مقبولة
        const val MAX_ACCURACY_METERS    = 50f   // حد رفض القراءة
        const val TIMEOUT_MS             = 60_000L // 60 ثانية

        fun formatCoordinate(value: Double): String = String.format("%.8f", value)

        fun formatTimestamp(timestamp: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

        fun getAccuracyColor(accuracy: Float): Int = when {
            accuracy < 10f -> android.graphics.Color.parseColor("#2E7D32")  // أخضر - ممتازة
            accuracy < 25f -> android.graphics.Color.parseColor("#1565C0")  // أزرق  - جيدة
            accuracy < 50f -> android.graphics.Color.parseColor("#F57C00")  // برتقالي - متوسطة
            else           -> android.graphics.Color.parseColor("#C62828")  // أحمر  - ضعيفة
        }

        fun getAccuracyLabel(accuracy: Float): String = when {
            accuracy < 10f -> "دقة فائقة (ممتازة)"
            accuracy < 25f -> "دقة جيدة"
            accuracy < 50f -> "دقة متوسطة"
            else           -> "دقة ضعيفة"
        }
    }

    // ── الحالة الداخلية ───────────────────────────────────────────────────────
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null
    private val goodReadings = mutableListOf<Location>()  // قراءات مقبولة (< 50م)
    private var bestSingleReading: Location? = null       // أفضل قراءة منفردة كاحتياطي
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    // ── بدء التقاط الموقع ────────────────────────────────────────────────────

    /**
     * فحص إذا كان GPS مفعّلاً قبل بدء الالتقاط
     * يستخدم LocationSettingsRequest للتحقق وطلب التفعيل إن لزم
     */
    fun checkGpsEnabled(
        onEnabled: () -> Unit,
        onDisabled: (resolvable: ResolvableApiException?) -> Unit
    ) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        val settingsClient = LocationServices.getSettingsClient(context)
        val task: Task<LocationSettingsResponse> = settingsClient.checkLocationSettings(settingsRequest)

        task.addOnSuccessListener {
            // GPS مفعّل
            onEnabled()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                // يمكن حل المشكلة بعرض حوار للمستخدم
                onDisabled(exception)
            } else {
                onDisabled(null)
            }
        }
    }

    /**
     * فحص سريع: هل GPS مفعّل في النظام؟
     */
    fun isGpsEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        onLocationUpdate: (location: Location, samples: Int, maxSamples: Int) -> Unit,
        onTimeout: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!hasPermission()) {
            onError("لا توجد صلاحية للموقع")
            return
        }

        goodReadings.clear()
        bestSingleReading = null

        // إعداد طلب الموقع عالي الدقة
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(true)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    processNewReading(location, onLocationUpdate)
                }
            }
        }

        // Timeout: تحذير إن لم تصل قراءة جيدة خلال 60 ثانية
        timeoutRunnable = Runnable {
            if (goodReadings.isEmpty()) {
                Log.w(TAG, "GPS timeout: no good reading within ${TIMEOUT_MS / 1000}s")
                onTimeout()
            }
        }.also { timeoutHandler.postDelayed(it, TIMEOUT_MS) }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            Log.d(TAG, "GPS updates started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS", e)
            onError("خطأ في تشغيل GPS: ${e.message}")
        }
    }

    private fun processNewReading(
        location: Location,
        onUpdate: (Location, Int, Int) -> Unit
    ) {
        // تتبّع أفضل قراءة منفردة كاحتياطي بغض النظر عن الجودة
        val best = bestSingleReading
        if (best == null || location.accuracy < best.accuracy) {
            bestSingleReading = location
        }

        // رفض القراءات الضعيفة جداً
        if (location.accuracy > MAX_ACCURACY_METERS) {
            Log.v(TAG, "Rejected reading: accuracy=${location.accuracy}m")
            return
        }

        goodReadings.add(location)
        if (goodReadings.size > MAX_SAMPLES) goodReadings.removeAt(0)

        val averaged = calculateWeightedAverage(goodReadings)
        onUpdate(averaged, goodReadings.size, MAX_SAMPLES)

        Log.v(TAG, "Good reading #${goodReadings.size}: accuracy=${location.accuracy}m → avg=${averaged.accuracy}m")
    }

    /**
     * Weighted Average: وزن كل قراءة = 1 / accuracy²
     * (القراءة الأدق تؤثر أكثر في المتوسط)
     *
     * تحسين على المتوسط البسيط الذي يتأثر بالقراءات الشاذة
     */
    private fun calculateWeightedAverage(locations: List<Location>): Location {
        if (locations.isEmpty()) return Location("empty")
        if (locations.size == 1) return locations[0]

        var weightSum = 0.0
        var latSum    = 0.0
        var lonSum    = 0.0

        for (loc in locations) {
            // الوزن = 1 / accuracy² (تجنّب القسمة على صفر)
            val weight = 1.0 / (loc.accuracy * loc.accuracy).coerceAtLeast(0.01f)
            weightSum += weight
            latSum    += loc.latitude  * weight
            lonSum    += loc.longitude * weight
        }

        val result = Location("weighted_average")
        result.latitude  = latSum / weightSum
        result.longitude = lonSum / weightSum

        // دقة محسّنة: المتوسط / sqrt(N) — تحسّن إحصائي للأخطاء العشوائية
        val meanAcc = locations.sumOf { it.accuracy.toDouble() } / locations.size
        result.accuracy = (meanAcc / sqrt(locations.size.toDouble())).toFloat()
        result.time = System.currentTimeMillis()

        return result
    }

    // ── إيقاف التقاط الموقع ──────────────────────────────────────────────────

    fun stopLocationUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
        goodReadings.clear()
        Log.d(TAG, "GPS updates stopped")
    }

    /**
     * أفضل موقع متاح: المتوسط الموزون إن أمكن، وإلا أفضل قراءة منفردة
     * مفيد عند الحفظ اليدوي قبل اكتمال العينات
     */
    fun getBestAvailableLocation(): Location? {
        return if (goodReadings.isNotEmpty()) calculateWeightedAverage(goodReadings)
        else bestSingleReading
    }

    fun hasGoodReadings(): Boolean = goodReadings.isNotEmpty()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}
