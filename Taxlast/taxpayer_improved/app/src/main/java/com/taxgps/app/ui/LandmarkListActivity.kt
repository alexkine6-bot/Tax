package com.taxgps.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Landmark
import com.taxgps.app.databinding.ActivityLandmarkListBinding
import com.taxgps.app.utils.DistanceHelper
import kotlinx.coroutines.launch

/**
 * شاشة إدارة المعالم المرجعية
 * عرض قائمة المعالم مع إمكانية التعديل والحذف
 */
class LandmarkListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLandmarkListBinding
    private lateinit var db: DatabaseHelper
    private lateinit var adapter: LandmarkAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLandmarkListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        db = DatabaseHelper.getInstance(this)

        setupRecyclerView()

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, LandmarkActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadLandmarks()
    }

    private fun setupRecyclerView() {
        adapter = LandmarkAdapter(
            onEdit = { landmark ->
                Intent(this, LandmarkActivity::class.java).also {
                    it.putExtra(LandmarkActivity.EXTRA_EDIT_ID, landmark.id)
                    startActivity(it)
                }
            },
            onDelete = { landmark ->
                confirmDelete(landmark)
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadLandmarks() {
        lifecycleScope.launch {
            val landmarks = db.getAllLandmarksAsync()
            adapter.submitList(landmarks)
            binding.tvEmpty.visibility = if (landmarks.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (landmarks.isEmpty()) View.GONE else View.VISIBLE
            binding.tvCount.text = "عدد المعالم: ${landmarks.size}"
        }
    }

    private fun confirmDelete(landmark: Landmark) {
        AlertDialog.Builder(this)
            .setTitle("حذف المعلم")
            .setMessage("هل تريد حذف \"${landmark.name}\"؟")
            .setPositiveButton("حذف") { _, _ ->
                lifecycleScope.launch {
                    db.deleteLandmarkAsync(landmark.id)
                    loadLandmarks()
                    Toast.makeText(this@LandmarkListActivity, "تم الحذف", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
