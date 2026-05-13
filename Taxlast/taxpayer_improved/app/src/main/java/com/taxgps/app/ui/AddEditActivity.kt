package com.taxgps.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityAddEditBinding
import com.taxgps.app.utils.LocationHelper
import kotlinx.coroutines.launch

/**
 * شاشة إضافة / تعديل المكلف المحسّنة
 *
 * التحسينات:
 * 1. Timeout 60 ثانية مع تحذير للمستخدم
 * 2. تحذير عند الحفظ إذا كانت الدقة ضعيفة (> 25م)
 * 3. زر "حفظ أفضل قراءة متاحة" كخيار احتياطي
 * 4. عداد تنازلي واضح: "3 من 10 قراءات جيدة"
 * 5. عرض أفضل دقة محققة حتى الآن
 */
class AddEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEditBinding
    private lateinit var db: DatabaseHelper
    private lateinit var locationHelper: LocationHelper

    private var editId: Long = -1
    private var capturedLat: Double?  = null
    private var capturedLon: Double?  = null
    private var capturedAcc: Float?   = null
    private var capturedAt:  Long?    = null
    private var isCapturing = false

    // ── صلاحية الموقع ────────────────────────────────────────────────────────

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startCapturingLocation()
        } else {
            Toast.makeText(this, getString(R.string.location_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    // ── دورة الحياة ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = DatabaseHelper.getInstance(this)
        locationHelper = LocationHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupStatusSpinner()

        editId = intent.getLongExtra(EXTRA_EDIT_ID, -1)
        if (editId != -1L) {
            binding.toolbar.title = getString(R.string.edit_taxpayer)
            loadExistingData()
        }

        binding.btnCaptureLocation.setOnClickListener {
            if (isCapturing) stopCapturingLocation(saveCurrentReading = true)
            else requestLocationCapture()
        }

        binding.btnSave.setOnClickListener { attemptSave() }
    }

    override fun onDestroy() {
        locationHelper.stopLocationUpdates()
        super.onDestroy()
    }

    // ── إعداد العناصر ────────────────────────────────────────────────────────

    private fun setupStatusSpinner() {
        binding.spinnerStatus.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Taxpayer.STATUS_LIST
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    // ── تحميل البيانات عند التعديل ───────────────────────────────────────────

    private fun loadExistingData() {
        lifecycleScope.launch {
            val t = db.getTaxpayerByIdAsync(editId) ?: return@launch
            with(binding) {
                etName.setText(t.name)
                etTaxNumber.setText(t.taxNumber)
                etIdNumber.setText(t.idNumber)
                etPhone.setText(t.phone)
                etAddress.setText(t.address)
                etActivityType.setText(t.activityType)
                etNotes.setText(t.notes)
                etNeighborRight.setText(t.neighborRight)
                etNeighborLeft.setText(t.neighborLeft)
                etShopDesc.setText(t.shopDescription)

                if (t.isOld()) rbOld.isChecked = true else rbNew.isChecked = true

                val statusIdx = Taxpayer.STATUS_LIST.indexOf(t.status)
                if (statusIdx >= 0) spinnerStatus.setSelection(statusIdx)

                if (t.hasLocation()) {
                    capturedLat = t.latitude
                    capturedLon = t.longitude
                    capturedAcc = t.accuracy
                    capturedAt  = t.capturedAt
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
        binding.btnCaptureLocation.text = "إيقاف وحفظ القراءة الحالية"
        binding.gpsProgressLayout.visibility = View.VISIBLE
        binding.averagingProgress.max      = LocationHelper.MAX_SAMPLES
        binding.averagingProgress.progress = 0

        locationHelper.startLocationUpdates(
            onLocationUpdate = { location, samples, max ->
                handleLocationUpdate(location, samples, max)
            },
            onTimeout = {
                // لم تصل قراءة جيدة خلال 60 ثانية
                showTimeoutDialog()
            },
            onError = { error ->
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                stopCapturingLocation(saveCurrentReading = false)
            }
        )
    }

    private fun handleLocationUpdate(location: Location, samples: Int, max: Int) {
        capturedLat = location.latitude
        capturedLon = location.longitude
        capturedAcc = location.accuracy
        capturedAt  = System.currentTimeMillis()

        binding.tvGpsProgress.text     = "جمع القراءة $samples من $max..."
        binding.averagingProgress.progress = samples

        updateLocationDisplay()

        // Auto-stop عند اكتمال العينات بدقة جيدة
        if (samples >= max && location.accuracy <= LocationHelper.GOOD_ACCURACY_METERS) {
            stopCapturingLocation(saveCurrentReading = false)
            Toast.makeText(this, "✓ تم تحقيق الدقة المطلوبة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCapturingLocation(saveCurrentReading: Boolean) {
        if (saveCurrentReading && !locationHelper.hasGoodReadings()) {
            // لا توجد قراءات جيدة — نحفظ أفضل قراءة متاحة كاحتياطي
            locationHelper.getBestAvailableLocation()?.let { loc ->
                capturedLat = loc.latitude
                capturedLon = loc.longitude
                capturedAcc = loc.accuracy
                capturedAt  = System.currentTimeMillis()
                updateLocationDisplay()
            }
        }
        isCapturing = false
        locationHelper.stopLocationUpdates()
        binding.btnCaptureLocation.text    = getString(R.string.recapture_location)
        binding.gpsProgressLayout.visibility = View.GONE
    }

    /** حوار Timeout: خيار حفظ أفضل قراءة متاحة */
    private fun showTimeoutDialog() {
        val bestLocation = locationHelper.getBestAvailableLocation()
        AlertDialog.Builder(this)
            .setTitle("انتهت مهلة GPS")
            .setMessage(
                if (bestLocation != null)
                    "لم تُحقَّق الدقة المطلوبة خلال 60 ثانية.\n" +
                    "أفضل دقة متاحة: ${bestLocation.accuracy.toInt()} متر\n\n" +
                    "هل تريد حفظ هذه القراءة الاحتياطية؟"
                else
                    "لم يتمكن GPS من تحديد موقعك.\nتأكد من أنك في مكان مفتوح وحاول مجدداً."
            )
            .apply {
                if (bestLocation != null) {
                    setPositiveButton("حفظ القراءة الاحتياطية") { _, _ ->
                        capturedLat = bestLocation.latitude
                        capturedLon = bestLocation.longitude
                        capturedAcc = bestLocation.accuracy
                        capturedAt  = System.currentTimeMillis()
                        updateLocationDisplay()
                        stopCapturingLocation(saveCurrentReading = false)
                    }
                }
                setNegativeButton("إلغاء") { _, _ ->
                    stopCapturingLocation(saveCurrentReading = false)
                }
            }
            .show()
    }

    // ── عرض معلومات الموقع ────────────────────────────────────────────────────

    private fun updateLocationDisplay() {
        val lat = capturedLat ?: return
        val lon = capturedLon ?: return

        with(binding) {
            tvLocationStatus.text    = "✓ تم تحديد الموقع"
            tvCoordinates.text       = "${LocationHelper.formatCoordinate(lat)}, ${LocationHelper.formatCoordinate(lon)}"
            tvCoordinates.visibility = View.VISIBLE

            capturedAcc?.let { acc ->
                tvAccuracyInfo.text      = "${LocationHelper.getAccuracyLabel(acc)} (${acc.toInt()} متر)"
                tvAccuracyInfo.setTextColor(LocationHelper.getAccuracyColor(acc))
                tvAccuracyInfo.visibility = View.VISIBLE
            }

            capturedAt?.let { ts ->
                tvCapturedAt.text       = "آخر تحديث: ${LocationHelper.formatTimestamp(ts)}"
                tvCapturedAt.visibility = View.VISIBLE
            }
        }
    }

    // ── الحفظ ─────────────────────────────────────────────────────────────────

    private fun attemptSave() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etName.error = getString(R.string.required_field)
            binding.etName.requestFocus()
            return
        }

        // تحذير إذا كانت الدقة ضعيفة عند الحفظ
        val acc = capturedAcc
        if (acc != null && acc > LocationHelper.GOOD_ACCURACY_METERS) {
            AlertDialog.Builder(this)
                .setTitle("تحذير: دقة ضعيفة")
                .setMessage(
                    "دقة الموقع الحالية ${acc.toInt()} متر.\n" +
                    "يُنصح بدقة أفضل من ${LocationHelper.GOOD_ACCURACY_METERS.toInt()} متر.\n\n" +
                    "هل تريد الحفظ بالدقة الحالية؟"
                )
                .setPositiveButton("حفظ على أي حال") { _, _ -> performSave(name) }
                .setNegativeButton("إعادة الالتقاط", null)
                .show()
        } else {
            performSave(name)
        }
    }

    private fun performSave(name: String) {
        val taxpayer = Taxpayer(
            id              = if (editId != -1L) editId else 0,
            name            = name,
            taxNumber       = binding.etTaxNumber.text.toString().trim(),
            idNumber        = binding.etIdNumber.text.toString().trim(),
            phone           = binding.etPhone.text.toString().trim(),
            address         = binding.etAddress.text.toString().trim(),
            activityType    = binding.etActivityType.text.toString().trim(),
            notes           = binding.etNotes.text.toString().trim(),
            type            = if (binding.rbOld.isChecked) Taxpayer.TYPE_OLD else Taxpayer.TYPE_NEW,
            status          = Taxpayer.STATUS_LIST[binding.spinnerStatus.selectedItemPosition],
            neighborRight   = binding.etNeighborRight.text.toString().trim(),
            neighborLeft    = binding.etNeighborLeft.text.toString().trim(),
            shopDescription = binding.etShopDesc.text.toString().trim(),
            latitude        = capturedLat,
            longitude       = capturedLon,
            accuracy        = capturedAcc,
            capturedAt      = capturedAt
        )

        lifecycleScope.launch {
            if (editId != -1L) db.updateTaxpayerAsync(taxpayer)
            else db.insertTaxpayerAsync(taxpayer)
            Toast.makeText(this@AddEditActivity, "تم حفظ البيانات بنجاح", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_EDIT_ID = "extra_edit_id"
    }
}
