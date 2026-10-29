package io.github.atc1441.oeplnfc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Keeping the app itself current, from the releases page it is published on.
 *
 * Every push to the default branch builds a signed APK and publishes it as a
 * release, so "the newest version" is exactly what the GitHub API calls the
 * latest release of the repository. This asks for it, compares it against what
 * is installed, downloads the APK and hands it to the package installer.
 *
 * Three rules shape what follows:
 *
 *  - Without a working network nothing is attempted. The phone that runs this
 *    is often standing in front of a shelf of price tags with no connectivity
 *    at all, and an app that stalls on a socket timeout at startup there is
 *    worse than an app that never checks.
 *
 *  - Nothing is installed that the phone would then refuse. An APK signed with
 *    a different key cannot replace this app - Android rejects it as a
 *    different app with the same name - and an unsigned one (which CI does
 *    produce when the signing secrets are missing) cannot be installed at all.
 *    Both are checked against the downloaded file before the installer is
 *    opened, because the installer's own error says only "App not installed".
 *
 *  - The decision to install is taken on the archive's own version, not on the
 *    release's name. The name is what the check reads off the releases page;
 *    the versionCode inside the APK is what the phone compares when it decides
 *    whether this is an upgrade.
 */
object AppUpdate {

    const val REPO = "atc1441/ATC_BLE_NFC_OEPL_Android_APK"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MIME = "application/vnd.android.package-archive"

    /**
     * Short on purpose. This runs while the user is waiting to tap a tag, and
     * a check that takes longer than that is a check that failed.
     */
    private const val CONNECT_MS = 8000
    private const val READ_MS = 12000

    /** What the releases page offers. */
    data class Release(
        val tag: String,
        val versionName: String,
        val assetName: String,
        val url: String,
        val size: Long
    ) {
        /**
         * CI names the file "-unsigned.apk" when the signing secrets are not
         * configured for a build. Android cannot install such a file at all,
         * so it is worth saying so rather than letting the installer fail.
         */
        val signed: Boolean get() = !assetName.endsWith("-unsigned.apk")
    }

    // --- what is published --------------------------------------------------

    /**
     * Pull the release out of the API's answer.
     *
     * Kept separate from the fetching so it can be tested against a recorded
     * answer: this is the part that breaks silently when the published asset
     * is renamed.
     */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name").ifEmpty { o.optString("name") }
        if (tag.isEmpty()) return null
        val assets = o.optJSONArray("assets") ?: return null

        var pick: Release? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!name.endsWith(".apk", true) || url.isEmpty()) continue
            val r = Release(tag, tag.removePrefix("v"), name, url, a.optLong("size", 0L))
            // A signed asset wins over an unsigned one in the same release,
            // whatever order they are listed in.
            if (pick == null || (!pick.signed && r.signed)) pick = r
        }
        return pick
    }

    /**
     * A release as one line of text, and back.
     *
     * The automatic check is rate limited, so between two checks the app has
     * to be able to go on offering what the last one found - otherwise the
     * offer would vanish on the next start and come back an hour later. The
     * separator is a character that cannot occur in a tag, a file name or a
     * URL, so nothing has to be escaped.
     */
    fun encodeRelease(r: Release): String =
        listOf(r.tag, r.assetName, r.size.toString(), r.url).joinToString("\u0001")

    fun decodeRelease(s: String): Release? {
        val p = s.split("\u0001")
        if (p.size != 4) return null
        val (tag, asset, size, url) = p
        val bytes = size.toLongOrNull() ?: return null
        // Not just shape: this is what a download will be pointed at, so it
        // has to be https and it has to be a release of this repository.
        if (tag.isEmpty() || asset.isEmpty()) return null
        if (!url.startsWith("https://github.com/$REPO/releases/")) return null
        return Release(tag, tag.removePrefix("v"), asset, url, bytes)
    }

    /** Ask the releases page what the newest version is. */
    @Throws(IOException::class)
    fun fetchLatest(): Release? {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            // The API answers 403 to a request without a User-Agent.
            setRequestProperty("User-Agent", "ATC_BLE_OEPL-NFC-app")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("the releases page answered $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(body)
        } finally {
            c.disconnect()
        }
    }

    // --- which of two versions is the newer one -----------------------------

    /**
     * The numbers in a version, in order: "v1.0.52" -> [1, 0, 52].
     *
     * Only the dotted number at the front is read, so a tag that picks up a
     * suffix one day ("v1.0.53-rc1") compares as 1.0.53 - rather than taking
     * the "1" out of "rc1" as a fourth position and counting as newer than the
     * release it is a candidate for.
     */
    fun versionNumbers(s: String): List<Int> {
        val head = Regex("^\\d+(\\.\\d+)*")
            .find(s.removePrefix("v").removePrefix("V")) ?: return emptyList()
        return head.value.split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * Is [release] a later version than [installed]?
     *
     * Compared number by number, a missing position counting as zero, so the
     * baseline a local build carries ("1.0") is older than anything CI
     * publishes ("1.0.52") rather than equal to it.
     */
    fun isNewer(release: String, installed: String): Boolean {
        val a = versionNumbers(release)
        val b = versionNumbers(installed)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // --- the network --------------------------------------------------------

    /**
     * Is there a network that actually reaches somewhere?
     *
     * `VALIDATED` is the part that matters: a phone associated with a captive
     * portal, or with wifi that has no route, reports a connection, and asking
     * only "is something connected" is what turns a quiet skip into a stalled
     * request at startup.
     */
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= 23) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    // --- downloading --------------------------------------------------------

    /**
     * Where a release is kept while it is being installed.
     *
     * In the cache, because once the installer has it the copy here is of no
     * further use and the system may take the space back whenever it likes.
     * Older downloads are removed on the way in: the directory holds the
     * release being installed and nothing else.
     */
    fun cacheFile(ctx: Context, r: Release): File {
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        val f = File(dir, r.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        dir.listFiles()?.forEach { if (it != f) it.delete() }
        return f
    }

    /**
     * Fetch the APK, reporting whole percent as it goes.
     *
     * The file is written under a temporary name and moved into place at the
     * end, so an interrupted download cannot leave something that looks like a
     * complete APK behind - a half APK that the installer rejects is a much
     * more confusing failure than no APK at all.
     */
    @Throws(IOException::class)
    fun download(r: Release, into: File, onProgress: (Int) -> Unit) {
        val tmp = File(into.parentFile, into.name + ".part")
        tmp.delete()
        val c = (URL(r.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ATC_BLE_OEPL-NFC-app")
        }
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("the download answered $code")
            val total = if (r.size > 0) r.size else c.contentLength.toLong()
            var done = 0L
            var shown = -1
            c.inputStream.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; onProgress(pct) }
                        }
                    }
                    out.flush()
                }
            }
            if (total > 0 && done != total)
                throw IOException("got $done of $total bytes")
            into.delete()
            if (!tmp.renameTo(into)) throw IOException("could not put the file in place")
        } finally {
            c.disconnect()
            tmp.delete()
        }
    }

    // --- installing ---------------------------------------------------------

    /** The versionCode inside an APK file, or 0 if it cannot be read. */
    fun archiveVersionCode(ctx: Context, file: File): Long {
        val info = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return 0
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()
    }

    /** The package name inside an APK file, or an empty string. */
    fun archivePackage(ctx: Context, file: File): String =
        ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName ?: ""

    /**
     * The signing certificate of an APK file, and of this app, as SHA-256.
     *
     * GET_SIGNATURES is deprecated in favour of GET_SIGNING_CERTIFICATES, but
     * it is the one that works on an archive on every API level this app
     * supports, and the two are being compared with each other - so as long as
     * both sides are read the same way, which one it is does not matter.
     */
    @Suppress("DEPRECATION")
    fun archiveSignature(ctx: Context, file: File): String? {
        val info = ctx.packageManager.getPackageArchiveInfo(
            file.absolutePath, PackageManager.GET_SIGNATURES
        ) ?: return null
        return info.signatures?.firstOrNull()?.let { sha256(it.toByteArray()) }
    }

    @Suppress("DEPRECATION")
    fun ownSignature(ctx: Context): String? {
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName, PackageManager.GET_SIGNATURES
        )
        return info.signatures?.firstOrNull()?.let { sha256(it.toByteArray()) }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    /** This app's own versionName and versionCode. */
    fun installedVersion(ctx: Context): Pair<String, Long> {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()
        return Pair(info.versionName ?: "?", code)
    }

    /**
     * May this app ask for a package to be installed?
     *
     * From Android 8 the permission in the manifest is not enough on its own:
     * the user grants it per app in the settings, and without it the installer
     * opens and immediately closes again.
     */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** The settings page where that permission is given. */
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:" + ctx.packageName))

    /**
     * Hand the file to the package installer.
     *
     * Through a FileProvider from Android 7, because a file:// URI pointing
     * into this app's own storage is not readable by the installer and raises
     * FileUriExposedException on the way out.
     */
    fun installIntent(ctx: Context, file: File): Intent {
        val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 24) {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            intent.setDataAndType(uri, MIME)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            @Suppress("DEPRECATION")
            intent.setDataAndType(Uri.fromFile(file), MIME)
        }
        return intent
    }

    /**
     * Everything that has to hold before the installer is worth opening, as a
     * sentence to show when it does not - or null when the file is good.
     */
    fun whyNotInstallable(ctx: Context, file: File): String? {
        if (!file.isFile || file.length() == 0L) return "the download is not there"
        val pkg = archivePackage(ctx, file)
        if (pkg.isEmpty()) return "the download is not a readable APK"
        if (pkg != ctx.packageName)
            return "the download is $pkg, which is a different app"
        val own = ownSignature(ctx)
        val theirs = archiveSignature(ctx, file)
        if (theirs == null) return "the download is not signed, so it cannot be installed"
        if (own != null && own != theirs)
            return "the download is signed with a different key than the installed " +
                "app, which Android would refuse - uninstall this one first, or " +
                "install the release by hand"
        val mine = installedVersion(ctx).second
        val code = archiveVersionCode(ctx, file)
        if (code in 1 until mine)
            return "the download is version code $code and $mine is installed, " +
                "so the phone would refuse it as a downgrade"
        return null
    }
}
