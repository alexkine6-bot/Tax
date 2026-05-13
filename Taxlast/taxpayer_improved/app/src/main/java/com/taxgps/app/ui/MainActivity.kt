package com.taxgps.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.taxgps.app.R
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityMainBinding
import com.taxgps.app.viewmodel.TaxpayerViewModel

/**
 * الشاشة الرئيسية المحسّنة
 *
 * التحسينات:
 * - استخدام ViewModel بدلاً من استدعاء DB مباشرة
 * - LiveData يُحدِّث الواجهة تلقائياً
 * - Debounce في البحث (معالج في ViewModel)
 * - عرض إحصائيات: الإجمالي / القدامى / الجدد / لديهم موقع
 * - ListAdapter (DiffUtil) بدلاً من notifyDataSetChanged
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TaxpayerViewModel by viewModels()
    private lateinit var adapter: TaxpayerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearchView()
        setupFilterButtons()
        observeViewModel()

        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddEditActivity::class.java))
        }
    }

    // ── إعداد القائمة ─────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = TaxpayerAdapter { taxpayer ->
            Intent(this, DetailActivity::class.java).also {
                it.putExtra(EXTRA_ID, taxpayer.id)
                startActivity(it)
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)  // تحسين أداء
    }

    // ── البحث ────────────────────────────────────────────────────────────────

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true.also {
                viewModel.onSearchQueryChanged(query ?: "")
            }
            override fun onQueryTextChange(newText: String?) = true.also {
                viewModel.onSearchQueryChanged(newText ?: "")
            }
        })
    }

    // ── أزرار الفلترة ─────────────────────────────────────────────────────────

    private fun setupFilterButtons() {
        binding.btnFilterAll.setOnClickListener { viewModel.onTypeFilterChanged("") }
        binding.btnFilterOld.setOnClickListener { viewModel.onTypeFilterChanged(Taxpayer.TYPE_OLD) }
        binding.btnFilterNew.setOnClickListener { viewModel.onTypeFilterChanged(Taxpayer.TYPE_NEW) }
    }

    // ── مراقبة ViewModel ──────────────────────────────────────────────────────

    private fun observeViewModel() {
        // قائمة المكلفين
        viewModel.taxpayers.observe(this) { list ->
            adapter.submitList(list)  // DiffUtil يتولى التحديث الذكي
            binding.tvEmpty.visibility    = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }

        // الإحصائيات
        viewModel.stats.observe(this) { stats ->
            binding.tvTotal.text    = "الإجمالي: ${stats.total}"
            binding.tvOldCount.text = "قدامى: ${stats.oldCount}"
            binding.tvNewCount.text = "جدد: ${stats.newCount}"
            binding.tvWithGps.text  = "لديهم موقع: ${stats.withLocation}"
        }

        // مؤشر التحميل
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        // رسائل الخطأ
        viewModel.errorMessage.observe(this) { msg ->
            msg?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    // ── تحديث عند العودة للشاشة ───────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    companion object {
        const val EXTRA_ID = "extra_taxpayer_id"
    }
}
