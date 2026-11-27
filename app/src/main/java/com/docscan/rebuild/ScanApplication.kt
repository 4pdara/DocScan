package com.docscan.rebuild

import android.app.Application
import androidx.room.Room
import com.docscan.rebuild.data.ScanDatabase
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.imaging.PageMarks
import com.docscan.rebuild.imaging.PageNet

class ScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PageMarks.install(this)
        // DocScan's page network (in the assets, its digest checked at build time); without it the
        // detector works alone. Read here, before any detection (half a megabyte, a few milliseconds).
        PageNet.install(runCatching { assets.open(PageNet.ASSET).use { it.readBytes() } }.getOrNull())
    }
    val database by lazy { Room.databaseBuilder(this, ScanDatabase::class.java, "rebuild-v1.db").addMigrations(ScanDatabase.MIGRATION_1_2, ScanDatabase.MIGRATION_2_3).build() }
    val store by lazy { ScanStore(this, database) }
}

