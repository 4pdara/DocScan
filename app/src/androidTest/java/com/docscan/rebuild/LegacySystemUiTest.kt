package com.docscan.rebuild

import android.Manifest
import android.graphics.*
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import com.docscan.rebuild.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Uses Android accessibility and the production main-thread recomposer, without a Compose test clock. */
@RunWith(AndroidJUnit4::class)
class LegacySystemUiTest {
    @get:Rule val camera=GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val context get()=ApplicationProvider.getApplicationContext<ScanApplication>()
    private val store get()=context.store
    private val ui get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun text(id:Int)=context.getString(id)
    private fun find(value:String):UiObject2=requireNotNull(ui.wait(Until.findObject(By.text(value)),15000)) {"Missing UI text: $value"}
    private fun tap(value:String){find(value).click()}
    private fun desc(id:Int):UiObject2=requireNotNull(ui.wait(Until.findObject(By.desc(text(id)).enabled(true)),15000)) {"Missing UI action: ${text(id)}"}
    private fun await(condition:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(!condition() && SystemClock.uptimeMillis()<end)SystemClock.sleep(100);assertTrue("Condition timed out",condition())}
    private fun fixture():File {
        val bitmap=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply{drawColor(Color.WHITE);val p=Paint().apply{color=Color.BLACK;textSize=48f};drawText("LEFT PAGE",60f,160f,p);drawText("RIGHT PAGE",550f,160f,p)}
        return File(context.cacheDir,"system-ui-${System.nanoTime()}.png").also{f->try{f.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun book(draft:Boolean=false):Notebook=runBlocking {
        val b=store.create("System UI ${System.nanoTime()}",draft=draft);val file=fixture();try{store.captured(b.id,file)}finally{file.delete()};b
    }
    private fun screenshot(name:String){val folder=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()};ui.takeScreenshot(File(folder,"system-$name.png"))}

    @Test fun blankCaptureConfirmsDiscardWithoutCreatingAFile(){
        val before=runBlocking{store.dao.allBooks().map{it.id}.toSet()}
        ActivityScenario.launch(MainActivity::class.java).use {
            desc(R.string.new_scan).click();find(text(R.string.capture_batch));desc(R.string.detect_bounds).click();ui.pressBack()
            find(text(R.string.discard_scan_title));tap(text(R.string.continue_scan));find(text(R.string.capture_batch))
            ui.pressBack();tap(text(R.string.discard_scan));find(text(R.string.home_tab))
            assertEquals(before,runBlocking{store.dao.allBooks().map{it.id}.toSet()});screenshot("empty-discard")
        }
    }

    @Test fun capturedOriginalStaysUncommittedUntilExplicitSave(){
        val before=runBlocking{store.dao.allBooks().map{it.id}.toSet()}
        ActivityScenario.launch(MainActivity::class.java).use {
            desc(R.string.new_scan).click();find(text(R.string.capture_batch));desc(R.string.detect_bounds).click()
            desc(R.string.capture).click()
            await{runBlocking{store.dao.drafts().first().any {it.book.id !in before && it.pageCount>0}}}
            val draft=runBlocking{store.dao.allBooks().first{it.id !in before}}
            val page=runBlocking{store.dao.snapshot(draft.id).single()};val digest=hash(store.source(page))
            assertTrue(store.source(page).length()>0);assertTrue(draft.draft)
            tap(text(R.string.done));desc(R.string.save).click()
            await{runBlocking{store.dao.book(draft.id)?.draft==false}};find(text(R.string.export_pdf))
            assertEquals(digest,hash(store.source(page)));screenshot("captured-saved")
        }
    }

    @Test fun recoveredSplitStaysDraftUntilSaveAndConfirmedDiscardRemovesBothHalves(){
        ActivityScenario.launch(MainActivity::class.java).use {
            for(save in listOf(true,false)) {
                val b=book(draft=true);val source=runBlocking{store.dao.snapshot(b.id).single()};val digest=hash(store.source(source))
                screenshot("draft-visible");tap(b.title)
                desc(R.string.more_actions).click();tap(text(R.string.split_book));tap(text(R.string.split_apply))
                await{runBlocking{store.dao.snapshot(b.id).size==2}}
                assertTrue(runBlocking{store.dao.book(b.id)!!.draft});assertTrue(runBlocking{store.dao.snapshot(b.id).all{it.edit().frame.area()<.6f}})
                assertEquals(digest,hash(store.source(source)))
                if(save) {
                    desc(R.string.save).click();await{runBlocking{store.dao.book(b.id)?.draft==false}}
                    assertEquals(2,runBlocking{store.dao.snapshot(b.id).size});assertTrue(runBlocking{store.dao.snapshot(b.id).all{it.edit().frame.area()<.6f}});assertEquals(digest,hash(store.source(source)))
                    find(text(R.string.export_pdf));ui.pressBack();find(text(R.string.home_tab))
                } else {
                    ui.pressBack();find(text(R.string.discard_scan_title));tap(text(R.string.discard_scan))
                    await{runBlocking{store.dao.book(b.id)==null} && !store.source(source).exists()}
                    find(text(R.string.home_tab))
                }
            };screenshot("split-save-discard")
        }
    }

    @Test fun permanentTrashDeletionRequiresConfirmationAndKeepsSharedOriginal(){
        val b=book();val page=runBlocking{store.dao.snapshot(b.id).single()}
        val copy=runBlocking{store.copyPages("Shared ${System.nanoTime()}",listOf(page.id))};val digest=hash(store.source(page));runBlocking{store.trash(setOf(b.id))}
        ActivityScenario.launch(MainActivity::class.java).use {
            desc(R.string.trash).click();tap(b.title);desc(R.string.delete_forever).click()
            find(text(R.string.delete_forever_hint));tap(text(R.string.cancel));assertNotNull(runBlocking{store.dao.book(b.id)})
            desc(R.string.delete_forever).click();tap(text(R.string.delete_forever));await{runBlocking{store.dao.book(b.id)==null}}
            assertEquals(digest,hash(store.source(page)));assertNotNull(runBlocking{store.dao.book(copy.id)});screenshot("permanent-delete")
        }
    }

    @Test fun compactSearchAndAllFiveToolGroupsAreVisible(){
        ActivityScenario.launch(MainActivity::class.java).use {
            val input=requireNotNull(ui.wait(Until.findObject(By.clazz("android.widget.EditText")),15000))
            assertTrue(input.visibleBounds.height()<ui.displayHeight*.08)
            tap(text(R.string.tools_tab))
            for(group in listOf(R.string.group_scan,R.string.group_import,R.string.group_convert,R.string.group_edit,R.string.group_help)) {
                val scroller=UiScrollable(UiSelector().scrollable(true))
                assertTrue("Missing tool group: ${text(group)}",scroller.scrollIntoView(UiSelector().text(text(group))))
            };screenshot("tool-groups")
        }
    }
}
