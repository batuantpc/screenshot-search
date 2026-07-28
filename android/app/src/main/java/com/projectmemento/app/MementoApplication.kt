package com.projectmemento.app

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger

/**
 * Coil'in tekil ImageLoader'ini kurar.
 *
 * Screenshot'lar 1080x2400 civari; grid'de her kart icin tam boyut decode etmek
 * hem bellegi hem scroll'u oldurur. Coil hedef boyuta gore downsample eder ve
 * sonucu bellek + disk cache'te tutar, boylece geri donusler aninda gelir.
 */
class MementoApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // Ayni surecte ML Kit OCR ve gorsel etiketleme modelleri de bellek
                    // tuketiyor; Coil'in tipik %25'i yerine %18'de biraktik.
                    .maxSizePercent(context, percent = 0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            // Kaynak zaten cihazdaki MediaStore; agi hic kullanmiyoruz.
            .networkCachePolicy(CachePolicy.DISABLED)
            // Dusuk RAM'li cihazlarda (test cihazi Galaxy A23) yarim bellek kullanimi.
            .allowRgb565(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            .crossfade(durationMillis = 180)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
}
