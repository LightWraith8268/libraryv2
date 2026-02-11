package com.inknironapps.libraryiq.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.inknironapps.libraryiq.BuildConfig
import com.inknironapps.libraryiq.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean
)

data class WhatsNewInfo(
    val versionName: String,
    val releaseNotes: String
)

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient()
    private val repo = BuildConfig.GITHUB_REPO
    private val token = BuildConfig.GITHUB_TOKEN
    private val prefs by lazy {
        context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    }

    /** True if app was sideloaded (not installed from Google Play). */
    val isSideloaded: Boolean by lazy {
        val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer != "com.android.vending"
    }

    private val lastSeenVersion: String?
        get() = prefs.getString("last_seen_version", null)

    fun shouldShowWhatsNew(): Boolean {
        val current = BuildConfig.VERSION_NAME
        val lastSeen = lastSeenVersion
        return lastSeen != null && lastSeen != current
    }

    fun isFirstLaunch(): Boolean = lastSeenVersion == null

    fun markVersionSeen() {
        prefs.edit().putString("last_seen_version", BuildConfig.VERSION_NAME).apply()
    }

    /** Builds a GitHub API request with optional token auth for private repos. */
    private fun githubRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
        if (token.isNotBlank()) {
            builder.header("Authorization", "token $token")
        }
        return builder.build()
    }

    suspend fun getWhatsNew(): WhatsNewInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = BuildConfig.VERSION_NAME
            val request = githubRequest(
                "https://api.github.com/repos/$repo/releases/tags/v$currentVersion"
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLog.d("AppUpdate", "No release found for v$currentVersion: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val release = org.json.JSONObject(body)
            val notes = release.optString("body", "")

            if (notes.isBlank()) return@withContext null

            WhatsNewInfo(
                versionName = currentVersion,
                releaseNotes = sanitizeNotes(notes)
            )
        } catch (e: Exception) {
            DebugLog.e("AppUpdate", "What's new fetch failed: ${e.message}")
            null
        }
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = githubRequest("https://api.github.com/repos/$repo/releases")

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DebugLog.e("AppUpdate", "GitHub API error: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val releases = JSONArray(body)
            if (releases.length() == 0) {
                DebugLog.d("AppUpdate", "No releases found for $repo")
                return@withContext null
            }

            val latest = releases.getJSONObject(0)
            val tagName = latest.getString("tag_name")
            val releaseNotes = latest.optString("body", "")

            // Parse version from tag (e.g. "v1.8.2" -> "1.8.2")
            val remoteVersion = tagName.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME
            val isNewer = isNewerVersion(remoteVersion, currentVersion)

            // Find matching APK asset - prefer matching build type, fall back to any APK
            val buildType = if (BuildConfig.DEBUG) "debug" else "release"
            val assets = latest.getJSONArray("assets")
            var downloadUrl: String? = null
            var fallbackUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    if (name.contains(buildType)) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                    if (fallbackUrl == null) {
                        fallbackUrl = asset.getString("browser_download_url")
                    }
                }
            }

            // Use fallback APK if specific build type not found
            downloadUrl = downloadUrl ?: fallbackUrl
            if (downloadUrl == null) {
                DebugLog.d("AppUpdate", "No APK found in release $tagName")
                return@withContext null
            }

            UpdateInfo(
                tagName = tagName,
                versionName = remoteVersion,
                downloadUrl = downloadUrl,
                releaseNotes = sanitizeNotes(releaseNotes),
                isNewer = isNewer
            )
        } catch (e: Exception) {
            DebugLog.e("AppUpdate", "Update check failed: ${e.message}")
            null
        }
    }

    fun downloadAndInstall(updateInfo: UpdateInfo) {
        val fileName = "LibraryIQ-${updateInfo.tagName}-${if (BuildConfig.DEBUG) "debug" else "release"}.apk"

        // Clean up old downloads
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir?.listFiles()?.filter { it.name.startsWith("LibraryIQ-") && it.name.endsWith(".apk") }
            ?.forEach { it.delete() }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
            .setTitle("LibraryIQ Update ${updateInfo.versionName}")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = downloadManager.enqueue(request)

        // Register receiver to install when download completes
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    installApk(File(downloadsDir, fileName))
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            DebugLog.e("AppUpdate", "Install failed: ${e.message}")
        }
    }

    /**
     * Extracts only changelog sections (Added, Changed, Fixed, Removed) from release notes.
     * Strips all other content like PR links, commit hashes, author attributions, and URLs.
     */
    private fun sanitizeNotes(raw: String): String {
        val lines = raw.lines()
        val result = mutableListOf<String>()
        var inChangelogSection = false
        val sectionPattern = Regex("""^###?\s*(Added|Changed|Fixed|Removed)\b""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            when {
                sectionPattern.containsMatchIn(line) -> {
                    inChangelogSection = true
                    result.add(line.trim())
                }
                line.trimStart().startsWith("### ") || line.trimStart().startsWith("## ") -> {
                    // Non-changelog section header — stop collecting
                    inChangelogSection = false
                }
                inChangelogSection && line.trimStart().startsWith("- ") -> {
                    // Changelog bullet — clean it up
                    val cleaned = line.trim()
                        .replace(Regex("""\s*\(#\d+\)"""), "")       // PR refs like (#42)
                        .replace(Regex("""\s*by @[\w-]+"""), "")      // author attributions
                        .replace(Regex("""https?://\S+"""), "")       // URLs
                        .replace(Regex("""Co-authored-by:.*"""), "")
                        .replace(Regex("""\s+$"""), "")
                    if (cleaned.length > 2) result.add(cleaned)
                }
                inChangelogSection && line.isBlank() -> {
                    result.add("")
                }
            }
        }

        val output = result.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
        // If no changelog sections found, fall back to basic cleanup
        if (output.isBlank()) {
            return raw
                .replace(Regex("""https://\S+"""), "")
                .replace(Regex("""\s*by @[\w-]+ in \S*"""), "")
                .replace(Regex("""\*?\*?\s*Full Changelog\s*:?\s*\S*\*?\*?"""), "")
                .replace(Regex("""Co-authored-by:.*"""), "")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        }
        return output
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
