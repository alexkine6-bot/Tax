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
 * التحسينات:
 * - فصل منطق البيانات عن Activity بالكامل
 * - debounce للبحث لتجنّب استعلامات متكررة أثناء الكتابة
 * - LiveData لتحديث الواجهة تلقائياً
 * - تحميل الإحصائيات منفصلاً
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

    /** استدعاء فوري عند التهيئة */
    init { loadData() }

    /**
     * بحث مع Debounce 300ms لتجنّب الاستعلام عند كل حرف
     */
    fun onSearchQueryChanged(query: String) {
        searchQuery = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            loadData()
        }
    }

    fun onTypeFilterChanged(type: String) {
        typeFilter = type
        loadData()
    }

    fun refresh() = loadData()

    private fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _taxpayers.value = db.getAllTaxpayersAsync(searchQuery, typeFilter)
                _stats.value     = db.getStatsAsync()
            } catch (e: Exception) {
                _errorMessage.value = "خطأ في تحميل البيانات: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() { _errorMessage.value = null }
}
