package com.screenshotsearch.app.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.screenshotsearch.app.data.DiscoveredScreenshot
import java.text.Normalizer
import java.util.Locale

class ScreenshotMediaScanner(
    private val context: Context,
) {
    /**
     * Eslesen TUM screenshot'lari partiler halinde tarar ve her partiyi cagirana verir.
     *
     * Neden ayri bir yol: sabit limitli [scan] ilk tam taramada kutuphanenin yalnizca en
     * yeni N kaydini goruyordu. Gerisi hic kesfedilmiyor, sonraki taramalar da tarih
     * filtreli oldugu icin bir daha ASLA gorulmuyordu - sessiz veri kaybi. Gercek olcekte
     * dogrulandi: 966 dosyanin 620'si alindi, 346'si kayboldu.
     *
     * Parti parti islemek bellegi de sinirli tutar; tek seferde on binlerce kaydi listeye
     * toplamak gerekmez.
     */
    fun scanAll(batchSize: Int = 400, onBatch: (List<DiscoveredScreenshot>) -> Unit) {
        val batch = mutableListOf<DiscoveredScreenshot>()
        forEachScreenshot(addedAfterSeconds = null) { discovered ->
            batch += discovered
            if (batch.size >= batchSize) {
                onBatch(batch.toList())
                batch.clear()
            }
            true
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    fun scan(limit: Int = 100, addedAfterSeconds: Long? = null): List<DiscoveredScreenshot> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val results = mutableListOf<DiscoveredScreenshot>()
        forEachScreenshot(addedAfterSeconds) { discovered ->
            results += discovered
            results.size < limit
        }
        return results
    }

    /**
     * MediaStore'u tek gecisle dolasir ve screenshot gibi gorunen her kaydi [onItem]'a verir.
     * [onItem] `false` donerse tarama erken biter.
     */
    private inline fun forEachScreenshot(
        addedAfterSeconds: Long?,
        onItem: (DiscoveredScreenshot) -> Boolean,
    ) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = addedAfterSeconds?.let { "${MediaStore.Images.Media.DATE_ADDED} >= ?" }
        val selectionArgs = addedAfterSeconds?.let { arrayOf(it.toString()) }

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex).orEmpty()
                val relativePath = if (pathIndex >= 0) cursor.getString(pathIndex) else null
                val bucket = cursor.getString(bucketIndex)
                if (!looksLikeScreenshot(displayName, relativePath, bucket)) continue

                val mediaStoreId = cursor.getLong(idIndex)
                val dateTaken = cursor.getLong(takenIndex)
                val dateAddedMillis = cursor.getLong(addedIndex) * 1_000L
                val keepGoing = onItem(
                    DiscoveredScreenshot(
                        mediaStoreId = mediaStoreId,
                        contentUri = ContentUris.withAppendedId(collection, mediaStoreId).toString(),
                        capturedAt = dateTaken.takeIf { it > 0L } ?: dateAddedMillis,
                        displayName = displayName,
                        relativePath = relativePath,
                    ),
                )
                if (!keepGoing) return
            }
        }
    }

    /**
     * Verilen MediaStore kimliklerinden hâlâ var olanları döndürür.
     * Sorgu parçalara bölünüyor: SQLite'ın 999 bind parametresi sınırı tek seferde aşılabilir.
     *
     * ÖNEMLİ: herhangi bir parça sorgusu başarısız olursa (`query` null döner) `null` döndürür.
     * "Sorgu çalışmadı" ile "kayıt silinmiş" aynı şey değil; ikisini karıştırmak kullanıcının
     * bütün kütüphanesini sessizce gizleyebilirdi. Çağıran taraf null gelince hiçbir şey yapmaz.
     */
    fun existingIds(mediaStoreIds: Collection<Long>): Set<Long>? {
        if (mediaStoreIds.isEmpty()) return emptySet()
        val alive = mutableSetOf<Long>()
        mediaStoreIds.chunked(QUERY_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media._ID} IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray(),
                null,
            ) ?: return null
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (it.moveToNext()) {
                    alive += it.getLong(idIndex)
                }
            }
        }
        return alive
    }

    internal fun looksLikeScreenshot(
        displayName: String,
        relativePath: String?,
        bucketName: String?,
    ): Boolean {
        val searchable = normalize(
            listOfNotNull(displayName, relativePath, bucketName).joinToString(" "),
        )
        return screenshotMarkers.any(searchable::contains)
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.lowercase(TURKISH), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('ı', 'i')
        .replace("[^a-z0-9]+".toRegex(), " ")

    private companion object {
        const val QUERY_CHUNK = 400
        val TURKISH: Locale = Locale.forLanguageTag("tr-TR")
        val screenshotMarkers = listOf(
            "screenshot",
            "screen shot",
            "screen capture",
            "ekran goruntusu",
            "ekran goruntuleri",
            "screenshots",
        )
    }
}
