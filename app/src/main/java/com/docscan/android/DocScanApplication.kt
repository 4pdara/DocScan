package com.docscan.android

import android.app.Application
import com.docscan.android.data.DocScanDatabase
import com.docscan.android.data.DocumentRepository

class DocScanApplication : Application() {
    val database by lazy { DocScanDatabase.open(this) }
    val repository by lazy { DocumentRepository(this, database) }
}
