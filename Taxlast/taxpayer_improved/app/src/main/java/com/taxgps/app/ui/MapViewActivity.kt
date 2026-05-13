package com.taxgps.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Landmark
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityMapViewBinding
import com.taxgps.app.utils.DistanceHelper
import com.taxgps.app.utils.LocationHelper
import com.taxgps.app.utils.TaxpayerDistance
import com.taxgps.app.utils.LandmarkDistance
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox as OsmBoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay

/**
 * شاشة الخريطة المتكاملة المحسّنة
 *
 * نظام خرائط متكامل لمدينة صغيرة مكتظة:
 * - عرض جميع المكلفين مع مواقعهم
 * - عرض المعالم المرجعية (مساجد، مدارس، تقاطعات...)
 * - حساب وعرض المسافات بالأمتار بين المحلات
 * - رسم خطوط ربط بين المحلات القريبة
 * - عرض أقرب معلم مرجعي لكل محل
 * - لوحة معلومات تفاعلية عند النقر
 * - فلترة حسب المنطقة ونوع المعلم
 */
class MapViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapViewBinding
    private lateinit var db: DatabaseHelper

    private var taxpayerId: Long = -1
    private var showAll: Boolean = false

    // البيانات المحمّلة
    private var allTaxpayers: List<Taxpayer> = emptyList()
    private var allLandmarks: List<Landmark> = emptyList()
    private var selectedTaxpayer: Taxpayer? = null

    // حالة العرض
    private var showConnections: Boolean = true
    private var showLandmarks: Boolean = true
    private var showDistanceLabels: Boolean = true
    private var connectionMaxDistance: Double = 150.0  // أمتار

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        binding = ActivityMapViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        db = DatabaseHelper.getInstance(this)
        taxpayerId = intent.getLongExtra(EXTRA_ID, -1)
        showAll = intent.getBooleanExtra(EXTRA_SHOW_ALL, false)

        setupMap()
        setupControls()
        loadData()
    }

    // ── إعداد الخريطة ─────────────────────────────────────────────────────────

    private fun setupMap() {
        with(binding.mapView) {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)

            // مقياس المسافة - مهم جداً للمدينة الصغيرة
            overlays.add(ScaleBarOverlay(this).apply {
                setCentred(true)
                setScaleBarOffset(200, 10)
            })
            overlays.add(CompassOverlay(this@MapViewActivity, this).apply { enableCompass() })
        }
    }

    // ── إعداد عناصر التحكم ────────────────────────────────────────────────────

    private fun setupControls() {
        // زر تبديل خطوط الربط
        binding.btnToggleConnections.setOnClickListener {
            showConnections = !showConnections
            binding.btnToggleConnections.text = if (showConnections) "إخفاء الروابط" else "إظهار الروابط"
            refreshMap()
        }

        // زر تبديل المعالم
        binding.btnToggleLandmarks.setOnClickListener {
            showLandmarks = !showLandmarks
            binding.btnToggleLandmarks.text = if (showLandmarks) "إخفاء المعالم" else "إظهار المعالم"
            refreshMap()
        }

        // زر تبديل المسافات
        binding.btnToggleDistances.setOnClickListener {
            showDistanceLabels = !showDistanceLabels
            refreshMap()
        }

        // فلتر المسافة
        binding.spinnerDistance.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("50م", "100م", "150م", "200م", "300م", "500م")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerDistance.setSelection(2) // 150م افتراضي
        binding.spinnerDistance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                connectionMaxDistance = when (pos) {
                    0 -> 50.0; 1 -> 100.0; 2 -> 150.0
                    3 -> 200.0; 4 -> 300.0; 5 -> 500.0
                    else -> 150.0
                }
                refreshMap()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // زر فتح Google Maps
        binding.btnOpenExternal.setOnClickListener { openInGoogleMaps() }

        // زر إضافة معلم
        binding.btnAddLandmark.setOnClickListener {
            startActivity(Intent(this, LandmarkActivity::class.java))
        }

        // زر إدارة المعالم
        binding.btnManageLandmarks.setOnClickListener {
            startActivity(Intent(this, LandmarkListActivity::class.java))
        }
    }

    // ── تحميل البيانات ────────────────────────────────────────────────────────

    private fun loadData() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                allTaxpayers = db.getTaxpayersWithLocationAsync()
                allLandmarks = db.getAllLandmarksAsync()

                if (taxpayerId != -1L) {
                    selectedTaxpayer = db.getTaxpayerByIdAsync(taxpayerId)
                }

                refreshMap()
                updateInfoPanel()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    // ── تحديث الخريطة ─────────────────────────────────────────────────────────

    private fun refreshMap() {
        binding.mapView.overlays.removeAll { it is Marker || it is Polyline }

        // رسم خطوط الربط بين المحلات القريبة
        if (showConnections && allTaxpayers.size > 1) {
            drawConnections()
        }

        // رسم المعالم المرجعية
        if (showLandmarks) {
            drawLandmarks()
        }

        // رسم علامات المكلفين
        drawTaxpayerMarkers()

        // ضبط مستوى التكبير والمركز
        adjustMapView()

        binding.mapView.invalidate()
    }

    // ── رسم خطوط الربط ───────────────────────────────────────────────────────

    private fun drawConnections() {
        val pairs = DistanceHelper.calculatePairwiseDistances(allTaxpayers, connectionMaxDistance)

        for (pair in pairs) {
            val polyline = Polyline(binding.mapView).apply {
                addPoint(GeoPoint(pair.taxpayer1.latitude!!, pair.taxpayer1.longitude!!))
                addPoint(GeoPoint(pair.taxpayer2.latitude!!, pair.taxpayer2.longitude!!))

                // لون وسمك الخط حسب المسافة
                outlinePaint.apply {
                    color = getConnectionColor(pair.distanceMeters)
                    strokeWidth = getConnectionWidth(pair.distanceMeters)
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                    alpha = 140
                }
            }
            binding.mapView.overlays.add(polyline)

            // تسمية المسافة في منتصف الخط
            if (showDistanceLabels && pair.distanceMeters > 20) {
                val midLat = (pair.taxpayer1.latitude!! + pair.taxpayer2.latitude!!) / 2
                val midLon = (pair.taxpayer1.longitude!! + pair.taxpayer2.longitude!!) / 2
                val distLabel = Marker(binding.mapView).apply {
                    position = GeoPoint(midLat, midLon)
                    title = "${pair.distanceMeters.toInt()} م"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setTextLabelFontSize(20)
                    setTextLabelBackgroundColor(Color.argb(180, 255, 255, 255))
                    setTextLabelForegroundColor(Color.DKGRAY)
                }
                binding.mapView.overlays.add(distLabel)
            }
        }
    }

    private fun getConnectionColor(distance: Double): Int = when {
        distance < 30 -> Color.parseColor("#4CAF50")   // أخضر - قريب جداً
        distance < 80 -> Color.parseColor("#2196F3")   // أزرق - قريب
        distance < 150 -> Color.parseColor("#FF9800")  // برتقالي - متوسط
        else -> Color.parseColor("#9E9E9E")            // رمادي - بعيد
    }

    private fun getConnectionWidth(distance: Double): Float = when {
        distance < 30 -> 4f
        distance < 80 -> 3f
        distance < 150 -> 2f
        else -> 1.5f
    }

    // ── رسم المعالم المرجعية ──────────────────────────────────────────────────

    private fun drawLandmarks() {
        for (landmark in allLandmarks) {
            if (!landmark.hasLocation()) continue

            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(landmark.latitude, landmark.longitude)
                title = "${Landmark.getTypeIcon(landmark.type)} ${landmark.name}"
                snippet = buildString {
                    append("نوع: ${landmark.type}")
                    if (landmark.area.isNotBlank()) append("\nمنطقة: ${landmark.area}")
                    if (landmark.description.isNotBlank()) append("\n${landmark.description}")

                    // أقرب مكلفين لهذا المعلم
                    val nearest = DistanceHelper.findNearestTaxpayers(
                        landmark.latitude, landmark.longitude, allTaxpayers, 3, 200.0
                    )
                    if (nearest.isNotEmpty()) {
                        append("\n\n--- أقرب المحلات ---")
                        for (n in nearest) {
                            append("\n• ${n.taxpayer.name}: ${n.formattedDistance()}")
                        }
                    }
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                // أيقونة خاصة بنوع المعلم
                setTextLabelFontSize(24)

                setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    showLandmarkInfo(landmark)
                    true
                }
            }
            binding.mapView.overlays.add(marker)
        }
    }

    // ── رسم علامات المكلفين ───────────────────────────────────────────────────

    private fun drawTaxpayerMarkers() {
        for (taxpayer in allTaxpayers) {
            if (!taxpayer.hasLocation()) continue

            val isSelected = taxpayer.id == selectedTaxpayer?.id
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(taxpayer.latitude!!, taxpayer.longitude!!)
                title = taxpayer.name
                snippet = buildSnippet(taxpayer)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    showTaxpayerInfo(taxpayer)
                    true
                }
            }
            binding.mapView.overlays.add(marker)
        }
    }

    private fun buildSnippet(taxpayer: Taxpayer): String = buildString {
        if (taxpayer.activityType.isNotBlank()) append("المهنة: ${taxpayer.activityType}")
        if (taxpayer.address.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("العنوان: ${taxpayer.address}")
        }
        taxpayer.accuracy?.let {
            if (isNotEmpty()) append("\n")
            append("دقة GPS: ${it.toInt()} م")
        }

        // أقرب معلم مرجعي
        val nearestLm = DistanceHelper.findNearestLandmark(taxpayer, allLandmarks)
        if (nearestLm != null) {
            append("\n\n▶ أقرب معلم: ${Landmark.getTypeIcon(nearestLm.landmark.type)} ${nearestLm.landmark.name}")
            append("\n   المسافة: ${nearestLm.formattedDistance()}")
        }

        // أقرب مكلفين (جيران)
        val neighbors = DistanceHelper.findDirectNeighbors(taxpayer, allTaxpayers, 3)
        if (neighbors.isNotEmpty()) {
            append("\n\n--- الجيران ---")
            for (n in neighbors) {
                append("\n• ${n.taxpayer.name}: ${n.formattedDistance()}")
            }
        }
    }

    // ── لوحة معلومات المكلف ───────────────────────────────────────────────────

    private fun showTaxpayerInfo(taxpayer: Taxpayer) {
        selectedTaxpayer = taxpayer
        binding.infoPanel.visibility = View.VISIBLE
        binding.tvInfoName.text = taxpayer.name
        binding.tvInfoDetails.text = buildString {
            if (taxpayer.activityType.isNotBlank()) append("${taxpayer.activityType}")
            if (taxpayer.address.isNotBlank()) append(" - ${taxpayer.address}")
        }

        // أقرب معلم مرجعي
        val nearestLm = DistanceHelper.findNearestLandmark(taxpayer, allLandmarks)
        binding.tvInfoNearestLandmark.text = if (nearestLm != null) {
            "أقرب معلم: ${Landmark.getTypeIcon(nearestLm.landmark.type)} ${nearestLm.landmark.name} (${nearestLm.formattedDistance()})"
        } else {
            "لا توجد معالم مرجعية قريبة"
        }

        // أقرب المحلات
        val neighbors = DistanceHelper.findDirectNeighbors(taxpayer, allTaxpayers, 4)
        binding.tvInfoNeighbors.text = if (neighbors.isNotEmpty()) {
            buildString {
                append("الجيران:")
                for (n in neighbors) {
                    append("\n  • ${n.taxpayer.name} → ${n.formattedDistance()}")
                }
            }
        } else {
            "لا توجد محلات مجاورة"
        }
    }

    private fun showLandmarkInfo(landmark: Landmark) {
        binding.infoPanel.visibility = View.VISIBLE
        binding.tvInfoName.text = "${Landmark.getTypeIcon(landmark.type)} ${landmark.name}"
        binding.tvInfoDetails.text = buildString {
            append("نوع: ${landmark.type}")
            if (landmark.area.isNotBlank()) append(" - ${landmark.area}")
        }

        val nearestTaxpayers = DistanceHelper.findNearestTaxpayers(
            landmark.latitude, landmark.longitude, allTaxpayers, 5, 300.0
        )
        binding.tvInfoNearestLandmark.text = "المحلات القريبة: ${nearestTaxpayers.size}"
        binding.tvInfoNeighbors.text = if (nearestTaxpayers.isNotEmpty()) {
            buildString {
                for (n in nearestTaxpayers) {
                    append("• ${n.taxpayer.name}: ${n.formattedDistance()}\n")
                }
            }.trimEnd()
        } else {
            "لا توجد محلات قريبة"
        }
    }

    // ── تحديث لوحة المعلومات العامة ──────────────────────────────────────────

    private fun updateInfoPanel() {
        val taxpayerCount = allTaxpayers.size
        val landmarkCount = allLandmarks.size

        binding.tvMapTaxpayerName.text = if (showAll || taxpayerId == -1L) {
            "الخريطة: $taxpayerCount محل + $landmarkCount معلم مرجعي"
        } else {
            selectedTaxpayer?.name ?: ""
        }

        binding.tvMapCoordinates.text = if (selectedTaxpayer != null && selectedTaxpayer!!.hasLocation()) {
            "${LocationHelper.formatCoordinate(selectedTaxpayer!!.latitude!!)}, ${LocationHelper.formatCoordinate(selectedTaxpayer!!.longitude!!)}"
        } else {
            "انقر على علامة لعرض التفاصيل"
        }
    }

    // ── ضبط عرض الخريطة ──────────────────────────────────────────────────────

    private fun adjustMapView() {
        if (selectedTaxpayer != null && selectedTaxpayer!!.hasLocation() && !showAll) {
            // تركيز على مكلف واحد مع جيرانه
            binding.mapView.controller.setCenter(
                GeoPoint(selectedTaxpayer!!.latitude!!, selectedTaxpayer!!.longitude!!)
            )
            binding.mapView.controller.setZoom(19.0) // مستوى تكبير عالٍ للمدينة المكتظة
        } else if (allTaxpayers.isNotEmpty() || allLandmarks.isNotEmpty()) {
            // إظهار كل النقاط
            val allPoints = mutableListOf<GeoPoint>()
            allTaxpayers.filter { it.hasLocation() }.forEach {
                allPoints.add(GeoPoint(it.latitude!!, it.longitude!!))
            }
            allLandmarks.filter { it.hasLocation() }.forEach {
                allPoints.add(GeoPoint(it.latitude, it.longitude))
            }

            if (allPoints.size == 1) {
                binding.mapView.controller.setCenter(allPoints[0])
                binding.mapView.controller.setZoom(18.0)
            } else if (allPoints.size > 1) {
                val boundingBox = OsmBoundingBox(
                    allPoints.maxOf { it.latitude },
                    allPoints.maxOf { it.longitude },
                    allPoints.minOf { it.latitude },
                    allPoints.minOf { it.longitude }
                )
                binding.mapView.post {
                    binding.mapView.zoomToBoundingBox(boundingBox, true, 80)
                }
            }
        } else {
            // موقع افتراضي (سوريا - جبلة)
            binding.mapView.controller.setCenter(GeoPoint(35.36, 35.93))
            binding.mapView.controller.setZoom(15.0)
        }
    }

    // ── فتح Google Maps ───────────────────────────────────────────────────────

    private fun openInGoogleMaps() {
        val t = selectedTaxpayer?.takeIf { it.hasLocation() }
        if (t != null) {
            val encodedName = Uri.encode(t.name)
            val uri = Uri.parse("geo:${t.latitude},${t.longitude}?q=${t.latitude},${t.longitude}($encodedName)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage("com.google.android.apps.maps")

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                val webUri = Uri.parse("https://maps.google.com/?q=${t.latitude},${t.longitude}")
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        } else {
            Toast.makeText(this, "اختر مكلفاً أولاً", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        // إعادة تحميل البيانات عند العودة (قد تكون أُضيفت معالم جديدة)
        loadData()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    companion object {
        const val EXTRA_ID = "extra_map_taxpayer_id"
        const val EXTRA_SHOW_ALL = "extra_show_all"
    }
}
