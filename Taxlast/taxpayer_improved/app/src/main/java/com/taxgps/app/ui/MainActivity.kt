package com.taxgps.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityMainBinding
import com.taxgps.app.utils.AccessDbImportHelper
import com.taxgps.app.utils.BackupHelper
import com.taxgps.app.viewmodel.TaxpayerViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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

    // ── ملتقط الملفات ─────────────────────────────────────────────────────────
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { startImport(it) }
    }

    // ── ملتقط النسخ الاحتياطية ───────────────────────────────────────────────
    private val backupPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importBackup(it) }
    }

    // ── إنشاء ملف النسخة الاحتياطية ─────────────────────────────────────────
    private val backupCreateLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { performExportBackup(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupSearchView()
        setupFilterButtons()
        observeViewModel()

        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddEditActivity::class.java))
        }
    }

    // ── قائمة الخيارات (استيراد + عرض الكل على الخريطة) ──────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_IMPORT, 0, "استيراد ملف Access")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_IMPORT_CSV, 1, "استيراد CSV")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_MAP_ALL, 2, getString(R.string.show_all_on_map))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_LANDMARKS, 3, getString(R.string.manage_landmarks))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_BACKUP_EXPORT, 4, getString(R.string.export_backup))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_BACKUP_IMPORT, 5, getString(R.string.import_backup))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_IMPORT -> {
                showImportDialog()
                true
            }
            MENU_IMPORT_CSV -> {
                filePickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/*"))
                true
            }
            MENU_MAP_ALL -> {
                Intent(this, MapViewActivity::class.java).also {
                    it.putExtra(MapViewActivity.EXTRA_SHOW_ALL, true)
                    startActivity(it)
                }
                true
            }
            MENU_LANDMARKS -> {
                startActivity(Intent(this, LandmarkListActivity::class.java))
                true
            }
            MENU_BACKUP_EXPORT -> {
                exportBackup()
                true
            }
            MENU_BACKUP_IMPORT -> {
                backupPickerLauncher.launch(arrayOf("*/*"))
                true
            }
            else -> super.onOptionsItemSelected(item)
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
            binding.tvWithGps.text  = "موقع: ${stats.withLocation}"
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

    // ── الاستيراد ─────────────────────────────────────────────────────────────

    private fun showImportDialog() {
        AlertDialog.Builder(this)
            .setTitle("استيراد ملف Access (.accdb)")
            .setMessage(
                "اختر ملف قاعدة بيانات Access (سجلات_الدخل_المقطوع.accdb)\n\n" +
                "سيتم استيراد البيانات التالية:\n" +
                "- السجل، اسم المكلف، اسم الأم\n" +
                "- رقم القرار، تاريخ القرار\n" +
                "- المهنة، العنوان\n" +
                "- مقدار الضريبة، الربح الصافي\n\n" +
                "هل تريد استبدال البيانات الحالية أم إضافة الجديدة فقط؟"
            )
            .setPositiveButton("استبدال الكل") { _, _ ->
                launchFilePicker(clearExisting = true)
            }
            .setNeutralButton("إضافة فقط") { _, _ ->
                launchFilePicker(clearExisting = false)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun launchFilePicker(clearExisting: Boolean) {
        importClearExisting = clearExisting
        filePickerLauncher.launch(arrayOf(
            "application/msaccess",
            "application/x-msaccess",
            "application/vnd.ms-access",
            "application/octet-stream",
            "*/*"
        ))
    }

    private var importClearExisting = false

    private fun startImport(uri: Uri) {
        val fileName = uri.lastPathSegment ?: "file"
        val isAccess = fileName.endsWith(".accdb", true) || fileName.endsWith(".mdb", true)
        val isCsv = fileName.endsWith(".csv", true)

        val db = DatabaseHelper.getInstance(this)
        val importHelper = AccessDbImportHelper(this, db)

        // إظهار مؤشر التحميل
        binding.progressBar.visibility = View.VISIBLE

        val listener = object : AccessDbImportHelper.ImportListener {
            override fun onProgress(current: Int, total: Int, message: String) {
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onFinished(result: AccessDbImportHelper.ImportResult) {
                binding.progressBar.visibility = View.GONE
                viewModel.refresh()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تم الاستيراد بنجاح")
                    .setMessage(
                        "مُضاف: ${result.added}\n" +
                        "مُحدَّث: ${result.updated}\n" +
                        "مُتخطَّى: ${result.skipped}\n" +
                        "أخطاء: ${result.errors}\n\n" +
                        "الإجمالي: ${result.total}"
                    )
                    .setPositiveButton("حسناً", null)
                    .show()
            }

            override fun onError(error: String) {
                binding.progressBar.visibility = View.GONE
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("خطأ في الاستيراد")
                    .setMessage(error)
                    .setPositiveButton("حسناً", null)
                    .show()
            }
        }

        lifecycleScope.launch {
            if (isAccess) {
                importHelper.importFromUri(uri, listener, importClearExisting)
            } else if (isCsv) {
                importHelper.importFromCsv(uri, listener, importClearExisting)
            } else {
                // محاولة كـ Access أولاً ثم CSV
                importHelper.importFromUri(uri, listener, importClearExisting)
            }
        }
    }

    companion object {
        const val EXTRA_ID = "extra_taxpayer_id"
        private const val MENU_IMPORT = 100
        private const val MENU_IMPORT_CSV = 101
        private const val MENU_MAP_ALL = 102
        private const val MENU_LANDMARKS = 103
        private const val MENU_BACKUP_EXPORT = 104
        private const val MENU_BACKUP_IMPORT = 105
    }

    // ── النسخ الاحتياطي ──────────────────────────────────────────────────────

    private fun exportBackup() {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date())
        backupCreateLauncher.launch("tax_backup_${timestamp}.taxbackup")
    }

    private fun performExportBackup(uri: Uri) {
        val backupHelper = BackupHelper(this, DatabaseHelper.getInstance(this))
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            backupHelper.exportBackup(uri, object : BackupHelper.BackupListener {
                override fun onProgress(message: String, percent: Int) {
                    binding.progressBar.visibility = View.VISIBLE
                }
                override fun onSuccess(message: String) {
                    binding.progressBar.visibility = View.GONE
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.backup_success))
                        .setMessage(message)
                        .setPositiveButton("حسناً", null)
                        .show()
                }
                override fun onError(error: String) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun importBackup(uri: Uri) {
        val backupHelper = BackupHelper(this, DatabaseHelper.getInstance(this))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_title))
            .setMessage(getString(R.string.backup_replace_warning))
            .setPositiveButton("استبدال الكل") { _, _ ->
                performImportBackup(backupHelper, uri, true)
            }
            .setNeutralButton("إضافة فقط") { _, _ ->
                performImportBackup(backupHelper, uri, false)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun performImportBackup(helper: BackupHelper, uri: Uri, replace: Boolean) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            helper.importBackup(uri, replace, object : BackupHelper.BackupListener {
                override fun onProgress(message: String, percent: Int) {
                    binding.progressBar.visibility = View.VISIBLE
                }
                override fun onSuccess(message: String) {
                    binding.progressBar.visibility = View.GONE
                    viewModel.refresh()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.restore_success))
                        .setMessage(message)
                        .setPositiveButton("حسناً", null)
                        .show()
                }
                override fun onError(error: String) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            })
        }
    }
}
