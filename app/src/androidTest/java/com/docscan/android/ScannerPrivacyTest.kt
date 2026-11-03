package com.docscan.android

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerPrivacyTest {
    @Suppress("DEPRECATION")
    @Test fun scannerDependencyDoesNotGiveDocScanNetworkPermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse(requested.contains("android.permission.INTERNET"))
        assertFalse(requested.contains("android.permission.ACCESS_NETWORK_STATE"))
    }
}
