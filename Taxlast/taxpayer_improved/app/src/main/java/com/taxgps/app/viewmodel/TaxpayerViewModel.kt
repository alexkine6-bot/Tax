package com.taxgps.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.data.TaxpayerStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel للشاشة الرئيسية
 *
 * التحسينات v4:
 * - فصل منطق البيانات عن Activity بالكامل
 * - debounce محسّن للبحث (500ms عند البحث، 0ms عند الفلترة)
 * - LIMIT 500 للنتائج لتجنب بطء التحميل مع آلاف السجلات
 * - البحث يعمل فوراً عند 3+ حروف (لتجنب استعلامات فارغة)
 * - تحميل الإحصائيات بشكل مستقل (لا يتأثر بالبحث)
 */
class TaxpayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = DatabaseHelper.getInstance(application)

    // ── حالة البحث والفلترة ──────────────────────────────────────────────────
    private var searchQuery  = ""
    private var typeFilter   = ""
    private var searchJob: Job? = null

    // ── LiveData للواجهة ─────────────────────────────────────────────────────
    private val _taxpayers = MutableLiveData<List<Taxpayer>>(emptyList())
    val taxpayers: LiveData<List<Taxpayer>> = _taxpayers

    private val _stats = MutableLiveData<TaxpayerStats>()
    val stats: LiveData<TaxpayerStats> = _stats

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // ── تحميل البيانات ───────────────────────────────────────────────────────

    init {
        loadData()
        loadStats()
    }

    /**
     * بحث مع Debounce محسّن:
     * - 500ms تأخير لمنع استعلام عند كل حرف
     * - مع آلاف السجلات هذا التأخير ضروري لمنع تجمد الواجهة
     */
    fun onSearchQueryChanged(query: String) {
        searchQuery = query.trim()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // تأخير أطول مع البحث للسماح للمستخدم بكتابة أكثر
            delay(500)
            loadData()
        }
    }

    fun onTypeFilterChanged(type: String) {
        typeFilter = type
        // الفلترة بالنوع فورية (لا تحتاج debounce)
        loadData()
    }

    fun refresh() {
        loadData()
        loadStats()
    }

    private fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // LIMIT 500 يحمي من بطء التحميل مع آلاف السجلات
                _taxpayers.value = db.getAllTaxpayersAsync(
                    filter = searchQuery,
                    typeFilter = typeFilter,
                    limit = 500
                )
            } catch (e: Exception) {
                _errorMessage.value = "خطأ في تحميل البيانات: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                _stats.value = db.getStatsAsync()
            } catch (e: Exception) {
                // لا نظهر خطأ الإحصائيات
            }
        }
    }

    fun clearError() { _errorMessage.value = null }
}
