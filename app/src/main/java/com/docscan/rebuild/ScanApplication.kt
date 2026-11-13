package com.docscan.rebuild

import android.app.Application
import androidx.room.Room
import com.docscan.rebuild.data.ScanDatabase
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.imaging.NativeCamScannerEnhancer
import com.docscan.rebuild.imaging.PageMarks

class ScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeCamScannerEnhancer.configureCache(cacheDir)
        PageMarks.install(this)
    }
    val database by lazy { Room.databaseBuilder(this, ScanDatabase::class.java, "rebuild-v1.db").addMigrations(ScanDatabase.MIGRATION_1_2, ScanDatabase.MIGRATION_2_3).build() }
    val store by lazy { ScanStore(this, database) }
}

