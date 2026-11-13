package com.docscan.rebuild.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from this app's GitHub releases. Only when the user asks: nothing is checked or sent in the background.
 * The request carries no personal data (a generic user agent, no account). A download is installed only when
 * its SHA-256 matches the release's SHA256SUMS.txt and it is signed with the same certificate as the installed
 * app; Android itself also refuses an update signed differently.
 */
object AppUpdater {
    private const val RELEASES = "https://api.github.com/repos/4pdara/DocScan/releases?per_page=30"
    private const val TAG_PREFIX = "scanner-v"

    data class Release(val version: String, val tag: String, val apkName: String, val apkUrl: String, val sumsUrl: String, val notes: String, val size: Long)

    /** Numeric comparison of dotted versions: 0.10.0 is newer than 0.9.1. Suffixes such as -preview are ignored. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest published scanner release newer than [current], with its APK and checksum file, or null. */
    fun latest(json: String, current: String): Release? {
        val list = JSONArray(json)
        val found = (0 until list.length()).mapNotNull { i ->
            val r = list.getJSONObject(i)
            val tag = r.optString("tag_name")
            // Scanner builds are published as pre-releases; drafts are never offered.
            if (r.optBoolean("draft") || !tag.startsWith(TAG_PREFIX)) return@mapNotNull null
            val version = tag.removePrefix(TAG_PREFIX)
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }
            val apk = all.firstOrNull { it.optString("name") == "DocScan-$version.apk" } ?: return@mapNotNull null
            val sums = all.firstOrNull { it.optString("name") == "SHA256SUMS.txt" } ?: return@mapNotNull null
            Release(version, tag, apk.getString("name"), apk.getString("browser_download_url"), sums.getString("browser_download_url"), r.optString("body"), apk.optLong("size"))
        }
        return found.filter { newer(it.version, current) }.maxWithOrNull { a, b -> if (newer(a.version, b.version)) 1 else if (newer(b.version, a.version)) -1 else 0 }
    }

    /** The SHA-256 listed for [name] in a sha256sum file, or null. */
    fun checksum(sums: String, name: String): String? = sums.lineSequence().map { it.trim() }
        .mapNotNull { line -> Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(line)?.groupValues }
        .firstOrNull { it[2].trim() == name }?.get(1)?.lowercase()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "DocScan-updater")
    }

    private fun text(url: String): String {
        val connection = open(url)
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { connection.disconnect() }
    }

    suspend fun check(current: String): Release? = withContext(Dispatchers.IO) { latest(text(RELEASES), current) }

    /** Downloads the release APK, checks its SHA-256 and its signing certificate, and returns the verified file. */
    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val expected = requireNotNull(checksum(text(release.sumsUrl), release.apkName)) { "No checksum for ${release.apkName}" }
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val target = File(folder, release.apkName)
        val connection = open(release.apkUrl)
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(128 * 1024); var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); digest.update(buffer, 0, n); done += n
                        if (total > 0) progress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                    out.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == expected) { "SHA-256 mismatch" }
            check(sameSigner(context, target)) { "Different signing certificate" }
            target
        } catch (failure: Throwable) { target.delete(); throw failure } finally { connection.disconnect() }
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo?): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else info?.signatures
        return signatures.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) } }.toSet()
    }

    @Suppress("DEPRECATION")
    fun sameSigner(context: Context, apk: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val mine = certificates(pm.getPackageInfo(context.packageName, flags))
        val theirs = certificates(archive)
        // When a certificate cannot be read here, Android's installer still refuses a different signer.
        if (mine.isEmpty() || theirs.isEmpty()) return true
        return theirs.any { it in mine }
    }

    /** Whether Android lets this app install updates; otherwise the user allows it once in Settings. */
    fun mayInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context) =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName))

    /** Hands the verified APK to Android's installer; Android asks the user to confirm. */
    fun install(context: Context, apk: File, onResult: (Boolean, String?) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val action = "${context.packageName}.UPDATE_STATUS"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                        }
                        else -> {
                            runCatching { c.applicationContext.unregisterReceiver(this) }
                            onResult(status == PackageInstaller.STATUS_SUCCESS, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            // Mutable: the installer adds the status to this explicit (package-bound) intent.
            val pending = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}
