package com.docscan.rebuild.update

import android.app.DownloadManager
import android.content.Context
import com.docscan.rebuild.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The update for the whole app, not for one screen: checking, the download (done by Android's download manager,
 * so it goes on when the user leaves the page or the app), verification and installation. Any screen can show
 * its state; the app asks to install as soon as a verified update is ready and the app is open.
 */
object UpdateController {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object Current : State
        data class Available(val release: AppUpdater.Release) : State
        /** [fraction] is null while the size is not known yet. */
        data class Downloading(val version: String, val fraction: Float?) : State
        data class Verifying(val version: String) : State
        /** Verified and waiting for the user; [error] tells why the last install attempt did not finish. */
        data class Ready(val version: String, val file: File, val error: String? = null) : State
        data class Installing(val version: String) : State
        data class Failed(val message: String) : State
    }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    /** On app start and return: drop a download the app was already updated with, or keep following one in progress. */
    fun resume(context: Context) {
        val app = context.applicationContext
        AppUpdater.cleanUp(app, BuildConfig.VERSION_NAME)
        val pending = AppUpdater.pending(app) ?: return
        // A verified update, or one already handed to Android's installer, is not checked again: doing so on every
        // return to the app (Android's install screen pauses it) put "install now?" back on screen in a loop.
        if (!settled(mutable.value, pending.version)) watch(app)
    }

    fun check(context: Context) {
        val app = context.applicationContext
        mutable.value = State.Checking
        scope.launch {
            mutable.value = try {
                AppUpdater.check(BuildConfig.VERSION_NAME)?.let { State.Available(it) } ?: State.Current
            } catch (e: CancellationException) { throw e } catch (e: Exception) { State.Failed(e.message ?: e.javaClass.simpleName) }
            if (mutable.value == State.Current) AppUpdater.cleanUp(app, BuildConfig.VERSION_NAME)
        }
    }

    fun download(context: Context, release: AppUpdater.Release) {
        val app = context.applicationContext
        mutable.value = State.Downloading(release.version, null)
        scope.launch {
            try { AppUpdater.start(app, release); watch(app) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.value = State.Failed(e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun watch(app: Context) {
        if (watching?.isActive == true) return
        watching = scope.launch {
            while (isActive) {
                val pending = AppUpdater.pending(app) ?: break
                val progress = withContext(Dispatchers.IO) { AppUpdater.progress(app) }
                when {
                    progress == null -> { AppUpdater.cancel(app); mutable.value = State.Failed("Download removed"); break }
                    progress.status == DownloadManager.STATUS_SUCCESSFUL -> {
                        mutable.value = State.Verifying(pending.version)
                        mutable.value = try { State.Ready(pending.version, AppUpdater.verify(app)) }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { State.Failed(e.message ?: e.javaClass.simpleName) }
                        break
                    }
                    progress.status == DownloadManager.STATUS_FAILED -> { AppUpdater.cancel(app); mutable.value = State.Failed("Download failed (${progress.reason})"); break }
                    else -> mutable.value = State.Downloading(pending.version, if (progress.total > 0) (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) else null)
                }
                delay(700)
            }
        }
    }

    /** Hands the verified APK to Android's installer, which asks the user to confirm. */
    fun install(context: Context) {
        val ready = mutable.value as? State.Ready ?: return
        val app = context.applicationContext
        mutable.value = State.Installing(ready.version)
        scope.launch {
            try {
                // Copying 100 MB into the installer session stays off the main thread.
                withContext(Dispatchers.IO) {
                    // Declined or failed: the verified file stays, so installing can be tried again.
                    AppUpdater.install(app, ready.file) { ok, message -> if (!ok) mutable.value = ready.copy(error = message ?: "Install failed") }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.value = ready.copy(error = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun cancel(context: Context) {
        watching?.cancel(); AppUpdater.cancel(context.applicationContext); mutable.value = State.Idle
    }
}

/** Whether the update of [version] is already verified, being verified or being installed, so nothing is redone. */
internal fun settled(current: UpdateController.State, version: String) = when (current) {
    is UpdateController.State.Verifying -> current.version == version
    is UpdateController.State.Ready -> current.version == version
    is UpdateController.State.Installing -> current.version == version
    else -> false
}
