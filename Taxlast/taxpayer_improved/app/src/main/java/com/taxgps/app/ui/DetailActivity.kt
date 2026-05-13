package com.taxgps.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Landmark
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityDetailBinding
import com.taxgps.app.utils.DistanceHelper
import com.taxgps.app.utils.LocationHelper
import kotlinx.coroutines.launch

/**
 * شاشة تفاصيل المكلف المحسّنة
 *
 * الإصلاح الرئيسي:
 * deleteTaxpayerAsync كانت معلّقة بتعليق — تم تفعيلها مع حوار تأكيد مزدوج
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var db: DatabaseHelper
    private var taxpayerId: Long = -1
    private var taxpayer: Taxpayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        db = DatabaseHelper.getInstance(this)
        taxpayerId = intent.getLongExtra(MainActivity.EXTRA_ID, -1)

        binding.btnEdit.setOnClickListener {
            Intent(this, AddEditActivity::class.java).also {
                it.putExtra(AddEditActivity.EXTRA_EDIT_ID, taxpayerId)
                startActivity(it)
            }
        }

        binding.btnDelete.setOnClickListener { confirmDelete() }

        binding.btnViewMap.setOnClickListener {
            taxpayer?.takeIf { it.hasLocation() }?.let {
                Intent(this, MapViewActivity::class.java).also { intent ->
                    intent.putExtra(MapViewActivity.EXTRA_ID, taxpayerId)
                    startActivity(intent)
                }
            }
        }

        binding.btnOpenGoogleMaps.setOnClickListener { openInGoogleMaps() }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    // ── تحميل بيانات المكلف ───────────────────────────────────────────────────

    private fun loadData() {
        lifecycleScope.launch {
            taxpayer = db.getTaxpayerByIdAsync(taxpayerId) ?: run { finish(); return@launch }
            val t = taxpayer!!

            with(binding) {
                tvName.text         = t.name
                tvTaxNumber.text    = buildString {
                    if (t.recordNumber > 0) append("سجل: ${t.recordNumber}")
                    if (t.accessDecisionNo.isNotBlank()) {
                        if (isNotEmpty()) append(" | ")
                        append("قرار: ${t.accessDecisionNo}")
                    }
                    if (t.taxNumber.isNotBlank()) {
                        if (isNotEmpty()) append(" | ")
                        append("ض: ${t.taxNumber}")
                    }
                    if (isEmpty()) append("غير محدد")
                }
                tvIdNumber.text     = t.motherName.ifBlank { t.idNumber.ifBlank { "غير محدد" } }
                tvPhone.text        = t.phone.ifBlank { "غير محدد" }
                tvAddress.text      = t.address.ifBlank { "غير محدد" }
                tvActivityType.text = t.activityType.ifBlank { "غير محدد" }
                tvNotes.text        = buildString {
                    if (t.notes.isNotBlank()) append(t.notes)
                    if (t.decisionDate.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("تاريخ القرار: ${t.decisionDate}")
                    }
                    if (t.taxAmount > 0) {
                        if (isNotEmpty()) append("\n")
                        append("مقدار الضريبة: ${t.taxAmount} ل.س")
                    }
                    if (t.netProfit > 0) {
                        if (isNotEmpty()) append("\n")
                        append("الربح الصافي: ${t.netProfit} ل.س")
                    }
                    if (t.workNumber.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("رقم العمل: ${t.workNumber}")
                    }
                    if (isEmpty()) append("لا توجد ملاحظات")
                }
                tvStatus.text       = t.status
                tvNeighborRight.text = t.neighborRight.ifBlank { "غير محدد" }
                tvNeighborLeft.text  = t.neighborLeft.ifBlank { "غير محدد" }
                tvShopDesc.text      = t.shopDescription.ifBlank { "غير محدد" }

                // شريحة النوع
                if (t.isOld()) {
                    tvTypeChip.text = Taxpayer.TYPE_OLD
                    tvTypeChip.setBackgroundResource(R.drawable.bg_chip_old)
                    tvTypeChip.setTextColor(getColor(R.color.oldType))
                } else {
                    tvTypeChip.text = Taxpayer.TYPE_NEW
                    tvTypeChip.setBackgroundResource(R.drawable.bg_chip_new)
                    tvTypeChip.setTextColor(getColor(R.color.newType))
                }

                // قسم الموقع
                if (t.hasLocation()) {
                    locationAvailableLayout.visibility = View.VISIBLE
                    tvNoLocation.visibility            = View.GONE
                    btnViewMap.isEnabled               = true
                    btnOpenGoogleMaps.isEnabled        = true

                    tvLatitude.text  = LocationHelper.formatCoordinate(t.latitude!!)
                    tvLongitude.text = LocationHelper.formatCoordinate(t.longitude!!)

                    t.accuracy?.let { acc ->
                        tvAccuracy.text      = "${acc.toInt()} متر"
                        tvAccuracy.setTextColor(LocationHelper.getAccuracyColor(acc))
                        tvAccuracyLabel.text = LocationHelper.getAccuracyLabel(acc)
                        tvAccuracyLabel.setTextColor(LocationHelper.getAccuracyColor(acc))
                    }

                    t.capturedAt?.let { ts ->
                        tvCapturedAt.text = "وقت الالتقاط: ${LocationHelper.formatTimestamp(ts)}"
                    }
                } else {
                    locationAvailableLayout.visibility = View.GONE
                    tvNoLocation.visibility            = View.VISIBLE
                    btnViewMap.isEnabled               = false
                    btnOpenGoogleMaps.isEnabled        = false
                }
            }
        }
    }

    // ── الحذف (كانت معلّقة — تم تفعيلها) ────────────────────────────────────

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("تأكيد الحذف")
            .setMessage("هل أنت متأكد من حذف المكلف \"${taxpayer?.name}\"؟\nلا يمكن التراجع عن هذا الإجراء.")
            .setPositiveButton("حذف") { _, _ -> performDelete() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun performDelete() {
        lifecycleScope.launch {
            val deleted = db.deleteTaxpayerAsync(taxpayerId)
            if (deleted > 0) {
                Toast.makeText(this@DetailActivity, "تم حذف المكلف بنجاح", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@DetailActivity, "فشل الحذف. حاول مرة أخرى.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── فتح Google Maps ───────────────────────────────────────────────────────

    private fun openInGoogleMaps() {
        val t = taxpayer?.takeIf { it.hasLocation() } ?: return
        val uri = Uri.parse("geo:${t.latitude},${t.longitude}?q=${t.latitude},${t.longitude}(${Uri.encode(t.name)})")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // Fallback: فتح المتصفح إن لم تكن خرائط Google مثبّتة
            val webUri = Uri.parse("https://maps.google.com/?q=${t.latitude},${t.longitude}")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    companion object {
        const val EXTRA_ID = "extra_taxpayer_id"
    }
}
