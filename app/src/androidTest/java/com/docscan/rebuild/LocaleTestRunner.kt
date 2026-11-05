package com.docscan.rebuild

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner

/** Select the app language before any activity is launched; never override an attached activity. */
class LocaleTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (Build.VERSION.SDK_INT >= 33) {
            val requested = InstrumentationRegistry.getArguments().getString("locale", "en")
            val language = if (requested == "he") "he" else "en"
            val command = "cmd locale set-app-locales ${targetContext.packageName} --user 0 --locales $language"
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
                it.readBytes()
            }
            waitForIdleSync()
        }
        super.onStart()
    }
}
