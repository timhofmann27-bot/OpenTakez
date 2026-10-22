package io.github.timhofmann27.opentakez.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.MediaInfo
import io.github.timhofmann27.opentakez.core.model.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The same DAO against the device's real SQLite (Robolectric uses its own). */
class DownloadDaoInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = DownloadRepository(db.downloadDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun lifecycleAndOrdering() = runBlocking {
        val info = MediaInfo(
            url = "https://soundcloud.com/a/b",
            platform = "soundcloud",
            kind = MediaKind.AUDIO,
            title = "Track",
            options = listOf(DownloadOption.AUDIO_MP3),
        )
        val first = repo.create(info, DownloadOption.AUDIO_MP3, Engine.DEVICE)
        Thread.sleep(2)
        val second = repo.create(info.copy(title = "Track 2"), DownloadOption.AUDIO_M4A, Engine.SERVER)
        repo.markRunning(first.id)
        repo.markDone(first.id, listOf("content://media/external/audio/media/1"), 0)

        assertEquals(listOf(second.id), repo.active.first().map { it.id })
        assertEquals(listOf(first.id), repo.done.first().map { it.id })
        assertEquals(listOf("content://media/external/audio/media/1"), repo.get(first.id)!!.files)
    }
}
