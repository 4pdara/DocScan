package com.docscan.rebuild

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.util.Locale

class LocaleTestRunner : AndroidJUnitRunner() {
    private var testLocale = Locale.ENGLISH
    override fun onCreate(arguments: Bundle) {
        testLocale = Locale.forLanguageTag(arguments.getString("locale", "en"))
        Locale.setDefault(testLocale)
        super.onCreate(arguments)
    }
    override fun callActivityOnCreate(activity: Activity, bundle: Bundle?) {
        activity.applyOverrideConfiguration(Configuration().apply { setLocale(testLocale); setLayoutDirection(testLocale) })
        super.callActivityOnCreate(activity, bundle)
    }
}
