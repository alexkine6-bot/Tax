package com.taxgps.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taxgps.app.R
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ItemTaxpayerBinding
import com.taxgps.app.utils.LocationHelper

/**
 * Adapter المحسّن باستخدام ListAdapter + DiffUtil
 *
 * التحسين الرئيسي:
 * استبدال notifyDataSetChanged() بـ DiffUtil — يُحدَّث فقط ما تغيّر
 * في القائمة بدلاً من إعادة رسم كل العناصر. مهم جداً مع آلاف السجلات.
 */
class TaxpayerAdapter(
    private val onClick: (Taxpayer) -> Unit
) : ListAdapter<Taxpayer, TaxpayerAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Taxpayer>() {
            /** السجل نفسه؟ (المفتاح) */
            override fun areItemsTheSame(old: Taxpayer, new: Taxpayer) = old.id == new.id

            /** المحتوى نفسه؟ (يمنع الرسم الزائد) */
            override fun areContentsTheSame(old: Taxpayer, new: Taxpayer) = old == new
        }
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(val binding: ItemTaxpayerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Taxpayer) = with(binding) {
            val ctx = root.context

            tvName.text = item.name

            tvTaxNumber.text = buildString {
                if (item.taxNumber.isNotBlank()) append("رقم ضريبي: ${item.taxNumber}")
                if (item.accessDecisionNo.isNotBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append("قرار: ${item.accessDecisionNo}")
                }
                if (isEmpty()) append("الرقم الضريبي: غير محدد")
            }

            tvPhone.text = if (item.phone.isNotBlank())
                "الهاتف: ${item.phone}" else "الهاتف: غير محدد"

            // شريحة النوع (قديم / جديد)
            if (item.isOld()) {
                tvTypeChip.text = Taxpayer.TYPE_OLD
                tvTypeChip.setBackgroundResource(R.drawable.bg_chip_old)
                tvTypeChip.setTextColor(ctx.getColor(R.color.oldType))
                typeBar.setBackgroundColor(ctx.getColor(R.color.oldType))
            } else {
                tvTypeChip.text = Taxpayer.TYPE_NEW
                tvTypeChip.setBackgroundResource(R.drawable.bg_chip_new)
                tvTypeChip.setTextColor(ctx.getColor(R.color.newType))
                typeBar.setBackgroundColor(ctx.getColor(R.color.newType))
            }

            // حالة الموقع
            if (item.hasLocation()) {
                tvLocationStatus.text = "📍 موقع محدد"
                tvLocationStatus.setTextColor(ctx.getColor(R.color.success))
                tvAccuracy.text = item.accuracy?.let { "±${it.toInt()}م" } ?: ""
                tvAccuracy.setTextColor(
                    item.accuracy?.let { LocationHelper.getAccuracyColor(it) }
                        ?: ctx.getColor(R.color.textSecondary)
                )
            } else {
                tvLocationStatus.text = "📍 لا يوجد موقع"
                tvLocationStatus.setTextColor(ctx.getColor(R.color.textSecondary))
                tvAccuracy.text = ""
            }

            root.setOnClickListener { onClick(item) }
        }
    }

    // ── Adapter overrides ─────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemTaxpayerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
