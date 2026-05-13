package com.taxgps.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityMapViewBinding
import com.taxgps.app.utils.LocationHelper
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay

/**
 * شاشة الخريطة المحسّنة
 *
 * التحسينات:
 * - استخدام getTaxpayersWithLocationAsync() المخصصة بدلاً من getAllTaxpayersAsync() الكاملة
 * - Fallback لـ Google Maps إن لم يكن التطبيق مثبتاً
 * - إضافة مؤشر تحميل أثناء جلب العلامات
 * - استخدام Uri.encode لأسماء المكلفين في الروابط
 */
class MapViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapViewBinding
    private lateinit var db: DatabaseHelper
    private var taxpayerId: Long = -1
    private var showAll: Boolean = false

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
        showAll    = intent.getBooleanExtra(EXTRA_SHOW_ALL, false)

        setupMap()
        loadMarkers()

        binding.btnOpenExternal.setOnClickListener { openInGoogleMaps() }
    }

    // ── إعداد الخريطة ─────────────────────────────────────────────────────────

    private fun setupMap() {
        with(binding.mapView) {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)

            overlays.add(ScaleBarOverlay(this).apply { setCentred(true) })
            overlays.add(CompassOverlay(this@MapViewActivity, this).apply { enableCompass() })
        }
    }

    // ── تحميل العلامات ────────────────────────────────────────────────────────

    private fun loadMarkers() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                if (showAll) loadAllMarkers()
                else loadSingleMarker()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.mapView.invalidate()
            }
        }
    }

    private suspend fun loadAllMarkers() {
        // نستخدم الاستعلام المخصص الذي يجلب فقط من لديهم إحداثيات
        val list = db.getTaxpayersWithLocationAsync()
        if (list.isEmpty()) {
            Toast.makeText(this, "لا يوجد مكلفون لديهم موقع محدد", Toast.LENGTH_SHORT).show()
            return
        }

        var firstPoint: GeoPoint? = null
        for (t in list) {
            val point = GeoPoint(t.latitude!!, t.longitude!!)
            addMarker(t, point)
            if (firstPoint == null) firstPoint = point
        }

        firstPoint?.let {
            binding.mapView.controller.setCenter(it)
            binding.mapView.controller.setZoom(14.0)
        }
        binding.tvMapTaxpayerName.text = "يعرض ${list.size} مكلف على الخريطة"
    }

    private suspend fun loadSingleMarker() {
        val t = db.getTaxpayerByIdAsync(taxpayerId)
        if (t == null || !t.hasLocation()) {
            Toast.makeText(this, "لا يوجد موقع محدد لهذا المكلف", Toast.LENGTH_SHORT).show()
            return
        }

        val point = GeoPoint(t.latitude!!, t.longitude!!)
        addMarker(t, point)
        binding.mapView.controller.setCenter(point)
        binding.mapView.controller.setZoom(18.0)

        binding.tvMapTaxpayerName.text  = t.name
        binding.tvMapCoordinates.text   =
            "${LocationHelper.formatCoordinate(t.latitude)}, ${LocationHelper.formatCoordinate(t.longitude)}"
    }

    private fun addMarker(t: Taxpayer, point: GeoPoint) {
        val marker = Marker(binding.mapView).apply {
            position = point
            title    = t.name
            snippet  = buildString {
                if (t.activityType.isNotBlank()) append(t.activityType)
                t.accuracy?.let { append("\nدقة: ${it.toInt()} م") }
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { m, _ ->
                m.showInfoWindow()
                binding.tvMapTaxpayerName.text = t.name
                binding.tvMapCoordinates.text  =
                    "${LocationHelper.formatCoordinate(t.latitude!!)}, ${LocationHelper.formatCoordinate(t.longitude!!)}"
                true
            }
        }
        binding.mapView.overlays.add(marker)
    }

    // ── فتح Google Maps ───────────────────────────────────────────────────────

    private fun openInGoogleMaps() {
        lifecycleScope.launch {
            val t = if (taxpayerId != -1L) db.getTaxpayerByIdAsync(taxpayerId) else null
            if (t != null && t.hasLocation()) {
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
            }
        }
    }

    override fun onResume()  { super.onResume();  binding.mapView.onResume() }
    override fun onPause()   { super.onPause();   binding.mapView.onPause() }

    companion object {
        const val EXTRA_ID       = "extra_map_taxpayer_id"
        const val EXTRA_SHOW_ALL = "extra_show_all"
    }
}
