package com.docscan.android

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.Configuration
import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscan.android.ui.DocScanApp
import com.docscan.android.ui.DocScanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveWorkflowDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val prefix = "Archive-${UUID.randomUUID().toString().take(8)} ספר"
    private val files = mutableListOf<File>()
    private val app get() = ui.activity.application as DocScanApplication
    private fun text(id: Int) = ui.activity.getString(id)
    private fun documents() = runBlocking { app.database.documents().allDocuments().filter { it.title == prefix } }
    private fun create(): String = runBlocking {
        val id = app.repository.create(prefix)
        val file = File(ui.activity.cacheDir, "${UUID.randomUUID()}.png").also { files.add(it) }
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val page = app.repository.importImage(id, Uri.fromFile(file), detectBounds = false)
        app.repository.rotate(page.id)
        app.repository.setAdjustments(page.id, com.docscan.android.processing.ImageAdjustments(sharpness = 32))
        id
    }
    private fun destination(): Pair<File, Uri> {
        val file = File(ui.activity.cacheDir, "exports/${UUID.randomUUID()}.zip").also { it.parentFile!!.mkdirs(); files.add(it) }
        return file to FileProvider.getUriForFile(ui.activity, "${ui.activity.packageName}.files", file)
    }
    private fun registry(uri: Uri, intents: MutableList<Intent>, cancelled: Boolean = false) = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            intents.add(contract.createIntent(ui.activity, input))
            dispatchResult(requestCode, if (cancelled) Activity.RESULT_CANCELED else Activity.RESULT_OK, Intent().setData(uri))
        }
    }
    private fun installRegistry(registry: ActivityResultRegistry, hebrew: Boolean = false): DocScanViewModel {
        val vm = ViewModelProvider(ui.activity)[DocScanViewModel::class.java]
        val activity = ui.activity
        val configuration = Configuration(activity.resources.configuration).apply { if (hebrew) setLocale(Locale.forLanguageTag("he")) }
        val context = activity.createConfigurationContext(configuration)
        ui.runOnUiThread {
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }, LocalContext provides context, LocalConfiguration provides configuration) {
                    DocScanTheme { DocScanApp(vm) }
                }
            }
        }
        ui.waitUntil(15_000) { !vm.busy.value }; ui.waitForIdle()
        return vm
    }
    private fun open(action: String, more: String = text(R.string.more_options)) {
        ui.onNodeWithContentDescription(more).performClick()
        ui.onNodeWithText(action).performClick()
    }
    private fun screenshot() {
        if (Build.VERSION.SDK_INT < 29) return
        val resolver = ui.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "archive-backup-he.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocScanTest")
        })!!
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @After fun cleanup(): Unit = runBlocking {
        documents().forEach { app.repository.deleteDocument(it.id) }; files.forEach { it.delete() }; Unit
    }

    @Test fun hebrewBackupAndRestoreUseDocumentContractsAndKeepEdits() {
        val id = create()
        val original = runBlocking { app.database.documents().pages(id).single() }
        val bytes = app.repository.source(original).readBytes()
        val (file, uri) = destination(); val intents = mutableListOf<Intent>()
        val vm = installRegistry(registry(uri, intents), hebrew = true)
        val he = ui.activity.createConfigurationContext(Configuration(ui.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("he")) }).resources
        ui.waitUntil(15_000) { ui.onAllNodesWithText(prefix).fetchSemanticsNodes().isNotEmpty() }
        open(he.getString(R.string.archive_backup), he.getString(R.string.more_options))
        ui.onNodeWithText(he.getString(R.string.archive_backup_hint)).assertExists()
        screenshot()
        ui.onNodeWithText(he.getString(R.string.archive_choose_destination)).performClick()
        ui.waitUntil(25_000) { !vm.busy.value && file.length() > 0 }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intents[0].action); assertEquals("application/zip", intents[0].type)
        runBlocking { app.repository.deleteDocument(id) }
        ui.waitUntil(15_000) { ui.onAllNodesWithText(prefix).fetchSemanticsNodes().isEmpty() }
        open(he.getString(R.string.archive_restore), he.getString(R.string.more_options))
        ui.onNodeWithText(he.getString(R.string.archive_choose_file)).performClick()
        ui.waitUntil(25_000) { !vm.busy.value && documents().size == 1 }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intents[1].action)
        val restored = documents().single(); assertNotEquals(id, restored.id)
        val page = runBlocking { app.database.documents().pages(restored.id).single() }
        assertEquals(90, page.rotation); assertEquals(32, page.sharpness)
        assertArrayEquals(bytes, app.repository.source(page).readBytes())
        assertNull(vm.archiveOperation.value)
    }
    @Test fun cancelledSystemPickersDoNotStartWorkOrChangeDocuments() {
        val id = create(); val (file, uri) = destination(); val intents = mutableListOf<Intent>()
        val vm = installRegistry(registry(uri, intents, cancelled = true))
        ui.waitUntil(15_000) { ui.onAllNodesWithText(prefix).fetchSemanticsNodes().isNotEmpty() }
        open(text(R.string.archive_backup)); ui.onNodeWithText(text(R.string.archive_choose_destination)).performClick()
        ui.waitForIdle(); assertFalse(file.exists()); assertNull(vm.archiveOperation.value)
        open(text(R.string.archive_restore)); ui.onNodeWithText(text(R.string.archive_choose_file)).performClick()
        ui.waitForIdle(); assertEquals(listOf(id), documents().map { it.id }); assertFalse(vm.busy.value)
    }
    @Test fun corruptArchiveShowsFailureAndKeepsExistingDocument() {
        val id = create(); val (file, uri) = destination(); file.writeText("not a backup")
        val vm = installRegistry(registry(uri, mutableListOf()))
        open(text(R.string.archive_restore)); ui.onNodeWithText(text(R.string.archive_choose_file)).performClick()
        ui.waitUntil(20_000) { !vm.busy.value && vm.message.value == text(R.string.archive_restore_failed) }
        assertEquals(listOf(id), documents().map { it.id })
        assertTrue(runBlocking { app.database.documents().pages(id).all { app.repository.source(it).exists() } })
    }
    @Test fun restoreConfirmationSurvivesActivityRecreation() {
        ui.waitForIdle()
        open(text(R.string.archive_restore))
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText(text(R.string.archive_restore_hint)).assertExists()
        ui.onNodeWithText(text(R.string.cancel)).performClick()
        ui.onNodeWithText(text(R.string.archive_restore_hint)).assertDoesNotExist()
    }
}
