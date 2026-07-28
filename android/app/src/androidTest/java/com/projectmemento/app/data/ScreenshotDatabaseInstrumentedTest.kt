package com.projectmemento.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotDatabaseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        databaseName = "memento-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun freshSchemaSeparatesMissingAndOcrFailureState() {
        ScreenshotDatabase(context, databaseName).use { helper ->
            val columns = helper.readableDatabase.rawQuery(
                "PRAGMA table_info(screenshot_items)",
                null,
            ).use { cursor ->
                buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }

            assertTrue("missing_since column", "missing_since" in columns)
            assertTrue("ocr_failure_kind column", "ocr_failure_kind" in columns)
            assertTrue("ocr_failure_at column", "ocr_failure_at" in columns)
        }
    }

    @Test
    fun migrationFromV8AddsFailureStateAndRequeuesLegacyEmptyRows() {
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE screenshot_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    media_store_id INTEGER NOT NULL UNIQUE,
                    content_uri TEXT NOT NULL,
                    captured_at INTEGER NOT NULL,
                    display_name TEXT NOT NULL,
                    relative_path TEXT,
                    ocr_text TEXT NOT NULL DEFAULT '',
                    ocr_layout TEXT NOT NULL DEFAULT '',
                    image_labels TEXT NOT NULL DEFAULT '',
                    search_text TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL DEFAULT '',
                    intent TEXT NOT NULL DEFAULT 'UNCLEAR',
                    topic TEXT NOT NULL DEFAULT 'OTHER',
                    confidence REAL NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'NEW',
                    processed INTEGER NOT NULL DEFAULT 0,
                    sensitive INTEGER NOT NULL DEFAULT 0,
                    user_corrected INTEGER NOT NULL DEFAULT 0,
                    user_topic_corrected INTEGER NOT NULL DEFAULT 0,
                    last_surfaced_at INTEGER,
                    source_hint TEXT,
                    missing_since INTEGER,
                    ocr_attempts INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE correction_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    screenshot_id INTEGER NOT NULL,
                    field TEXT NOT NULL,
                    previous_value TEXT NOT NULL,
                    new_value TEXT NOT NULL,
                    previous_confidence REAL NOT NULL DEFAULT 0,
                    previous_was_user INTEGER NOT NULL DEFAULT 0,
                    classifier_version INTEGER NOT NULL DEFAULT 0,
                    corrected_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            legacy.insertOrThrow(
                "screenshot_items",
                null,
                ContentValues().apply {
                    put("media_store_id", 8L)
                    put("content_uri", "content://test/8")
                    put("captured_at", 8L)
                    put("display_name", "Screenshot_8.png")
                    put("processed", 1)
                    put("ocr_attempts", 3)
                },
            )
            legacy.version = 8
        }

        ScreenshotDatabase(context, databaseName).use { helper ->
            helper.writableDatabase.query(
                "screenshot_items",
                arrayOf("processed", "ocr_attempts", "ocr_failure_kind", "ocr_failure_at"),
                "media_store_id = 8",
                null,
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
        }
    }

    @Test
    fun unavailableRowsStayHiddenUntilExplicitRetry() {
        ScreenshotDatabase(context, databaseName).use { helper ->
            helper.insertDiscovered(
                listOf(
                    DiscoveredScreenshot(
                        mediaStoreId = 42L,
                        contentUri = "content://test/42",
                        capturedAt = 42L,
                        displayName = "Screenshot_42.png",
                        relativePath = "Pictures/Screenshots",
                    ),
                ),
            )
            val pending = helper.nextPending()
            assertNotNull(pending)
            requireNotNull(pending)
            helper.markUnavailable(pending.id, "unreadable", 100L)

            assertTrue(helper.list().isEmpty())
            assertNull(helper.nextPending())
            assertEquals(1, helper.diagnostics()["okunamadi"])

            // Otomatik tarama aynı URI'yi yeniden gördüğünde kalıcı hata temizlenmemeli.
            helper.insertDiscovered(
                listOf(
                    DiscoveredScreenshot(
                        mediaStoreId = 42L,
                        contentUri = "content://test/42",
                        capturedAt = 43L,
                        displayName = "Screenshot_42.png",
                        relativePath = "Pictures/Screenshots",
                    ),
                ),
            )
            assertNull(helper.nextPending())

            assertEquals(1, helper.resetOcrAttempts())
            assertNotNull(helper.nextPending())
            assertFalse(helper.diagnostics().isEmpty())
            assertEquals(0, helper.diagnostics()["okunamadi"])
        }
    }

    @Test
    fun keysetPaginationReturnsEveryItemExactlyOnceBeyondOneThousandRows() {
        ScreenshotDatabase(context, databaseName).use { helper ->
            val expected = 1_205
            helper.insertDiscovered(
                (1..expected).map { index ->
                    DiscoveredScreenshot(
                        mediaStoreId = index.toLong(),
                        contentUri = "content://test/$index",
                        // Aynı timestamp'ler özellikle var: cursor'un ikinci anahtarı id test edilir.
                        capturedAt = (index / 7).toLong(),
                        displayName = "Screenshot_$index.png",
                        relativePath = "Pictures/Screenshots",
                    )
                },
            )

            val seen = mutableListOf<Long>()
            var cursor: ScreenshotCursor? = null
            do {
                val page = helper.page(cursor = cursor, limit = 60)
                assertEquals(expected, page.totalCount)
                assertTrue(page.items.size <= 60)
                seen += page.items.map(ScreenshotItem::id)
                cursor = page.nextCursor
            } while (cursor != null)

            assertEquals(expected, seen.size)
            assertEquals(expected, seen.toSet().size)
            assertEquals(expected, helper.librarySummary().totalCount)
            assertEquals(expected, helper.librarySummary().pendingCount)
        }
    }
}
