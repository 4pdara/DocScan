package com.docscan.rebuild

import android.app.Application
import androidx.room.Room
import com.docscan.rebuild.data.ScanDatabase
import com.docscan.rebuild.data.ScanStore

class ScanApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ScanDatabase::class.java, "rebuild-v1.db").addMigrations(ScanDatabase.MIGRATION_1_2).build() }
    val store by lazy { ScanStore(this, database) }
}

