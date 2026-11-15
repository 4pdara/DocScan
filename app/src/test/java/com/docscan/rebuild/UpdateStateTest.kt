package com.docscan.rebuild

import com.docscan.rebuild.update.UpdateController.State
import com.docscan.rebuild.update.settled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateStateTest {
    /** 1.2.0 report: returning to the app while Android's installer was open asked "install now?" again and again. */
    @Test fun aVerifiedOrInstallingUpdateIsNotCheckedAgainOnReturn() {
        val apk = File("DocScan-1.2.2.apk")
        assertTrue(settled(State.Ready("1.2.2", apk), "1.2.2"))
        assertTrue(settled(State.Ready("1.2.2", apk, "Install failed"), "1.2.2"))
        assertTrue(settled(State.Installing("1.2.2"), "1.2.2"))
        assertTrue(settled(State.Verifying("1.2.2"), "1.2.2"))
        // A download still running, a newer download, or a fresh start is followed as before.
        assertFalse(settled(State.Downloading("1.2.2", .5f), "1.2.2"))
        assertFalse(settled(State.Ready("1.2.2", apk), "1.2.3"))
        assertFalse(settled(State.Idle, "1.2.2"))
        assertFalse(settled(State.Failed("Download failed"), "1.2.2"))
    }
}
