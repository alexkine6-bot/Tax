package com.taxgps.app

import android.app.Application
import org.osmdroid.config.Configuration

/**
 * Application class للتطبيق
 *
 * المهام:
 * - تهيئة OSMDroid configuration
 * - إعداد User-Agent للخرائط
 * - تهيئة أي مكتبات تحتاج تهيئة مبكرة
 */
class TaxGpsApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // تهيئة OSMDroid — مطلوب قبل استخدام MapView
        Configuration.getInstance().apply {
            load(this@TaxGpsApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            // مسار التخزين المؤقت لبلاطات الخرائط
            osmdroidTileCache = cacheDir.resolve("osmdroid_tiles")
        }
    }
}
