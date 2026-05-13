package com.taxgps.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Landmark
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityLandmarkBinding
import com.taxgps.app.utils.LocationHelper
import kotlinx.coroutines.launch

/**
 * شاشة إضافة / تعديل معلم مرجعي
 *
 * المعالم المرجعية هي نقاط ثابتة معروفة في المدينة
 * تساعد في تحديد مواقع المحلات نسبياً
 * مثل: مساجد، مدارس، تقاطعات، دوائر حكومية، أسواق
 */
class LandmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLandmarkBinding
    private lateinit var db: DatabaseHelper
    private lateinit var locationHelper: LocationHelper

    private var editId: Long = -1
    private var capturedLat: Double? = null
    private var capturedLon: Double? = null
    private var capturedAcc: Float? = null
    private var isCapturing = false

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startCapturingLocation()
        } else {
            Toast.makeText(this, getString(R.string.location_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLandmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = DatabaseHelper.getInstance(this)
        locationHelper = LocationHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupTypeSpinner()
        setupAreaSpinner()

        editId = intent.getLongExtra(EXTRA_EDIT_ID, -1)
        if (editId != -1L) {
            binding.toolbar.title = "تعديل معلم مرجعي"
            loadExistingData()
        }

        binding.btnCaptureLocation.setOnClickListener {
            if (isCapturing) stopCapturingLocation()
            else requestLocationCapture()
        }

        binding.btnSave.setOnClickListener { attemptSave() }
    }

    override fun onDestroy() {
        locationHelper.stopLocationUpdates()
        super.onDestroy()
    }

    // ── إعداد القوائم المنسدلة ───────────────────────────────────────────────

    private fun setupTypeSpinner() {
        binding.spinnerType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Landmark.TYPE_LIST
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun setupAreaSpinner() {
        // المناطق المعروفة + خيار "أخرى"
        val areas = Taxpayer.KNOWN_AREAS + listOf("أخرى")
        binding.spinnerArea.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            areas
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    // ── تحميل البيانات عند التعديل ───────────────────────────────────────────

    private fun loadExistingData() {
        lifecycleScope.launch {
            val landmark = db.getLandmarkByIdAsync(editId) ?: return@launch
            with(binding) {
                etName.setText(landmark.name)
                etDescription.setText(landmark.description)
                cbMainReference.isChecked = landmark.isMainReference

                // تحديد النوع
                val typeIdx = Landmark.TYPE_LIST.indexOf(landmark.type)
                if (typeIdx >= 0) spinnerType.setSelection(typeIdx)

                // تحديد المنطقة
                val areaIdx = Taxpayer.KNOWN_AREAS.indexOf(landmark.area)
                if (areaIdx >= 0) spinnerArea.setSelection(areaIdx)
                else {
                    spinnerArea.setSelection(Taxpayer.KNOWN_AREAS.size) // "أخرى"
                    etAreaCustom.setText(landmark.area)
                    etAreaCustom.visibility = View.VISIBLE
                }

                if (landmark.hasLocation()) {
                    capturedLat = landmark.latitude
                    capturedLon = landmark.longitude
                    capturedAcc = landmark.accuracy
                    updateLocationDisplay()
                }
            }
        }
    }

    // ── التقاط الموقع GPS ─────────────────────────────────────────────────────

    private fun requestLocationCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startCapturingLocation()
        } else {
            locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun startCapturingLocation() {
        isCapturing = true
        binding.btnCaptureLocation.text = "إيقاف الالتقاط"
        binding.gpsProgressLayout.visibility = View.VISIBLE
        binding.averagingProgress.max = LocationHelper.MAX_SAMPLES
        binding.averagingProgress.progress = 0

        locationHelper.startLocationUpdates(
            onLocationUpdate = { location, samples, max ->
                capturedLat = location.latitude
                capturedLon = location.longitude
                capturedAcc = location.accuracy
                binding.tvGpsProgress.text = "قراءة $samples من $max..."
                binding.averagingProgress.progress = samples
                updateLocationDisplay()

                if (samples >= max && location.accuracy <= LocationHelper.GOOD_ACCURACY_METERS) {
                    stopCapturingLocation()
                    Toast.makeText(this, "تم تحديد الموقع بدقة", Toast.LENGTH_SHORT).show()
                }
            },
            onTimeout = {
                val best = locationHelper.getBestAvailableLocation()
                if (best != null) {
                    capturedLat = best.latitude
                    capturedLon = best.longitude
                    capturedAcc = best.accuracy
                    updateLocationDisplay()
                }
                stopCapturingLocation()
                Toast.makeText(this, "تم حفظ أفضل قراءة متاحة", Toast.LENGTH_SHORT).show()
            },
            onError = { error ->
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                stopCapturingLocation()
            }
        )
    }

    private fun stopCapturingLocation() {
        isCapturing = false
        locationHelper.stopLocationUpdates()
        binding.btnCaptureLocation.text = getString(R.string.recapture_location)
        binding.gpsProgressLayout.visibility = View.GONE
    }

    private fun updateLocationDisplay() {
        val lat = capturedLat ?: return
        val lon = capturedLon ?: return

        with(binding) {
            tvLocationStatus.text = "تم تحديد الموقع"
            tvCoordinates.text = "${LocationHelper.formatCoordinate(lat)}, ${LocationHelper.formatCoordinate(lon)}"
            tvCoordinates.visibility = View.VISIBLE

            capturedAcc?.let { acc ->
                tvAccuracyInfo.text = "${LocationHelper.getAccuracyLabel(acc)} (${acc.toInt()} متر)"
                tvAccuracyInfo.setTextColor(LocationHelper.getAccuracyColor(acc))
                tvAccuracyInfo.visibility = View.VISIBLE
            }
        }
    }

    // ── الحفظ ─────────────────────────────────────────────────────────────────

    private fun attemptSave() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etName.error = "اسم المعلم مطلوب"
            binding.etName.requestFocus()
            return
        }

        if (capturedLat == null || capturedLon == null) {
            Toast.makeText(this, "يجب تحديد موقع المعلم على الخريطة", Toast.LENGTH_LONG).show()
            return
        }

        val selectedArea = if (binding.spinnerArea.selectedItemPosition < Taxpayer.KNOWN_AREAS.size) {
            Taxpayer.KNOWN_AREAS[binding.spinnerArea.selectedItemPosition]
        } else {
            binding.etAreaCustom.text.toString().trim()
        }

        val landmark = Landmark(
            id = if (editId != -1L) editId else 0,
            name = name,
            type = Landmark.TYPE_LIST[binding.spinnerType.selectedItemPosition],
            description = binding.etDescription.text.toString().trim(),
            area = selectedArea,
            latitude = capturedLat!!,
            longitude = capturedLon!!,
            accuracy = capturedAcc,
            isMainReference = binding.cbMainReference.isChecked
        )

        lifecycleScope.launch {
            if (editId != -1L) db.updateLandmarkAsync(landmark)
            else db.insertLandmarkAsync(landmark)
            Toast.makeText(this@LandmarkActivity, "تم حفظ المعلم المرجعي", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_EDIT_ID = "extra_landmark_edit_id"
    }
}
