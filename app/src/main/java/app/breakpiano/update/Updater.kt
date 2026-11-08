package app.breakpiano.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.breakpiano.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val publishedAt: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: Release, val file: File) : UpdateState
    data class NeedsPermission(val release: Release, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update through GitHub Releases. Every push to main triggers the CI workflow, which builds
 * a signed APK and publishes it as release "build-<run number>". The app polls the latest release
 * while it's open and online, and offers a one-tap update when the build number is newer.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle); private set
    /** True once the user dismissed the prompt for this session — the badge stays visible. */
    var dismissed by mutableStateOf(false)

    val repo: String = BuildConfig.GITHUB_REPO
    val configured: Boolean get() = !repo.startsWith("OWNER/")
    private var pollJob: Job? = null
    private var lastCheck = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (System.currentTimeMillis() - lastCheck > 60_000) scope.launch { check(silent = true) }
        }
    }

    fun startWatching() {
        if (!configured) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback,
            )
        }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                check(silent = true)
                delay(15 * 60 * 1000L)
            }
        }
    }

    fun stopWatching() {
        pollJob?.cancel()
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
    }

    suspend fun check(silent: Boolean = false) {
        if (!configured) { if (!silent) state = UpdateState.Failed("Updates aren't configured for this build."); return }
        if (state is UpdateState.Downloading || state is UpdateState.ReadyToInstall) return
        lastCheck = System.currentTimeMillis()
        if (!silent) state = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        result.onSuccess { rel ->
            state = if (rel != null && rel.versionCode > BuildConfig.VERSION_CODE) {
                if (state !is UpdateState.Available) dismissed = false
                UpdateState.Available(rel)
            } else UpdateState.UpToDate
        }.onFailure {
            if (!silent) state = UpdateState.Failed("Couldn't reach GitHub. Check your connection.")
            else if (state is UpdateState.Checking) state = UpdateState.Idle
        }
    }

    private fun fetchLatest(): Release? {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Break-Android")
        }
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            var url: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url"); size = a.optLong("size"); break
                }
            }
            return Release(
                versionCode = code,
                versionName = json.optString("name").ifBlank { tag },
                notes = json.optString("body").trim(),
                apkUrl = url ?: return null,
                sizeBytes = size,
                publishedAt = json.optString("published_at"),
            )
        } finally {
            conn.disconnect()
        }
    }

    fun download(release: Release) {
        scope.launch {
            state = UpdateState.Downloading(release, 0f)
            val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, "Break-${release.versionCode}.apk")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    var url = URL(release.apkUrl)
                    var conn: HttpURLConnection
                    var redirects = 0
                    while (true) {   // GitHub redirects to its CDN
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 15_000; readTimeout = 30_000
                            setRequestProperty("User-Agent", "Break-Android")
                        }
                        val code = conn.responseCode
                        if (code in 300..399 && redirects++ < 5) {
                            url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
                        }
                        if (code !in 200..299) error("HTTP $code")
                        break
                    }
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastEmit > 80) {
                                    lastEmit = now
                                    val p = (read.toFloat() / total).coerceIn(0f, 1f)
                                    withContext(Dispatchers.Main) { state = UpdateState.Downloading(release, p) }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                }.isSuccess
            }
            if (!ok) { file.delete(); state = UpdateState.Failed("Download failed. Try again in a moment."); return@launch }
            state = UpdateState.ReadyToInstall(release, file)
            install(release, file)
        }
    }

    fun install(release: Release, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            state = UpdateState.NeedsPermission(release, file)
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("break.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, InstallResultReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    session.commit(pi.intentSender)
                }
            }.onFailure {
                withContext(Dispatchers.Main) { state = UpdateState.Failed("Install failed: ${it.message}") }
            }
        }
    }

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Called from onResume — continues an install that was waiting for permission. */
    fun onResume() {
        val s = state
        if (s is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) install(s.release, s.file)
    }

    fun reset() { state = UpdateState.Idle }
}
