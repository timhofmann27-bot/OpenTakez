package io.github.timhofmann27.opentakez.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Reads the resource files directly, so a missing German string fails here and not on a user's phone. */
class StringsParityTest {
    private data class Entry(val text: String, val translatable: Boolean)

    private fun load(dir: String): Map<String, Entry> {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("missing ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to Entry(e.textContent, e.getAttribute("translatable") != "false")
        }
    }

    private val en = load("values")
    private val de = load("values-de")
    private val translatable = en.filterValues { it.translatable }

    // Matches %s, %d, %1$s, %2$d; "%%" is a literal percent and is not a placeholder.
    private fun placeholders(text: String): List<String> =
        Regex("""%(?:\d+\$)?[sd]""").findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    @Test
    fun `English and German have the same keys`() {
        assertEquals(translatable.keys.sorted(), de.keys.sorted())
    }

    @Test
    fun `placeholders are identical per key`() {
        translatable.forEach { (key, entry) ->
            assertEquals("placeholders of $key", placeholders(entry.text), placeholders(de.getValue(key).text))
        }
    }

    @Test
    fun `multiple placeholders are positional`() {
        (en + de).forEach { (key, entry) ->
            val found = placeholders(entry.text)
            if (found.size > 1) assertTrue("$key must use %1\$s style: ${entry.text}", found.all { it.contains('$') })
        }
    }

    @Test
    fun `no German string is just the English one unless it is a name or a loan word`() {
        val sameAllowed = setOf(
            "nav_downloads", "url_label", "preview_format", "option_video_1080", "option_video_720",
            "option_audio_mp3", "option_audio_m4a", "option_image_original", "option_group_video",
            "settings_server", "engine_label", "notif_channel_downloads", "a11y_withTitle", "about_version",
        )
        translatable.forEach { (key, entry) ->
            if (key !in sameAllowed && entry.text.length > 12) {
                assertTrue("$key looks untranslated", entry.text != de.getValue(key).text)
            }
        }
    }

    @Test
    fun `every PWA key that applies to the app exists`() {
        // Spot check of the groups the contract names (url, media, option, job, history, settings, token, error, nav).
        val expected = listOf(
            "url_label", "url_placeholder", "url_paste", "url_clear", "url_submit",
            "media_items", "media_truncated",
            "option_video_best", "option_group_video", "option_group_audio", "option_group_image",
            "job_queued", "job_downloading", "job_processing", "job_done", "job_retry", "job_remove",
            "history_empty", "history_clear", "history_again",
            "settings_serverUrl", "settings_token", "settings_test", "settings_connected", "settings_language",
            "token_title", "token_body", "token_save", "token_cancel", "token_wrong",
            "error_noLink", "error_unsupported", "error_network", "error_unknown", "error_rateLimited", "error_busy",
            "nav_home", "nav_downloads", "nav_history", "nav_settings",
            // contract section 9
            "engine_label", "engine_device", "engine_server", "error_needsServer", "error_youtubeBlocked",
            "error_loginRequiredServer", "error_storageFull", "engine_preparing", "ytdlp_version", "ytdlp_update",
            "ytdlp_updating", "ytdlp_updated", "ytdlp_updateFailed", "notif_channel_downloads", "notif_done",
            "notif_failed", "perm_notifications_rationale", "saved_to", "job_cancel", "settings_defaultEngine",
            "settings_about",
        )
        expected.forEach { key ->
            assertTrue("values/strings.xml lacks $key", key in en)
            assertTrue("values-de/strings.xml lacks $key", key in de)
        }
    }
}
