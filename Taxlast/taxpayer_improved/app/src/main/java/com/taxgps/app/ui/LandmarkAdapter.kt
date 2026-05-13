package com.taxgps.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taxgps.app.data.Landmark
import com.taxgps.app.databinding.ItemLandmarkBinding
import com.taxgps.app.utils.LocationHelper

/**
 * Adapter لعرض المعالم المرجعية في القائمة
 */
class LandmarkAdapter(
    private val onEdit: (Landmark) -> Unit,
    private val onDelete: (Landmark) -> Unit
) : ListAdapter<Landmark, LandmarkAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Landmark>() {
            override fun areItemsTheSame(old: Landmark, new: Landmark) = old.id == new.id
            override fun areContentsTheSame(old: Landmark, new: Landmark) = old == new
        }
    }

    inner class ViewHolder(val binding: ItemLandmarkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Landmark) = with(binding) {
            tvIcon.text = Landmark.getTypeIcon(item.type)
            tvName.text = item.name
            tvType.text = item.type
            tvArea.text = if (item.area.isNotBlank()) item.area else "غير محدد"

            tvCoordinates.text = if (item.hasLocation()) {
                "${LocationHelper.formatCoordinate(item.latitude)}, ${LocationHelper.formatCoordinate(item.longitude)}"
            } else "لا يوجد موقع"

            tvMainBadge.visibility = if (item.isMainReference) android.view.View.VISIBLE else android.view.View.GONE

            if (item.description.isNotBlank()) {
                tvDescription.text = item.description
                tvDescription.visibility = android.view.View.VISIBLE
            } else {
                tvDescription.visibility = android.view.View.GONE
            }

            btnEdit.setOnClickListener { onEdit(item) }
            btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemLandmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
