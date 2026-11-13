package com.fem.linexa.ui.screens.desktop

import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fem.linexa.R
import java.net.NetworkInterface

/**
 * Pure URL logic for the WebView **run mode** of the Desktop screen
 * (Desktop Modes work, 2026-08).
 *
 * The mode starts a guest-side web server on a loopback port and renders it
 * in the platform WebView. Everything that is string arithmetic lives here so
 * `DesktopWebModeTest` can pin it on the JVM; everything that needs Android
 * (the WebView itself) lives in [DesktopWebViewPanel] below.
 *
 * Cleartext note: the WebView loads `http://127.0.0.1:<port>` — plain HTTP.
 * Android 9+ blocks cleartext by default, and no `network_security_config.xml`
 * shipped before Desktop Modes, so the manifest now carries one that permits
 * cleartext **only** for loopback hosts (`127.0.0.1`, `localhost`). The
 * `base-config` stays `cleartextTrafficPermitted="false"`, which is also what
 * the platform default already enforced for the app's other (HTTPS) traffic —
 * the file widens nothing it does not name.
 */
internal object DesktopWebMode {

    /**
     * The URL the WebView panel loads for a server on [port]. Loopback by
     * construction — the guest shares the host network stack, so a guest
     * listener on `[port]` is reachable here and nowhere else.
     */
    fun buildUrl(port: Int, path: String = "/"): String {
        val trimmedPath = when {
            path.isEmpty() -> "/"
            path.startsWith("/") -> path
            else -> "/$path"
        }
        return "http://127.0.0.1:$port$trimmedPath"
    }

    /**
     * [url] rewritten so another device on the LAN can open it, or null when
     * [lanIp] is null/blank — "Share" without a LAN address to substitute
     * shares a URL that cannot work, so the action is disabled instead.
     *
     * The guest server binds all interfaces (it is the user's own service),
     * so replacing the loopback host with the device's LAN IP exposes it to
     * the network the phone is on — the same deliberate trade the reference
     * Tiny Container app makes, stated here because it is a *choice*.
     */
    fun lanShareUrl(url: String, lanIp: String?): String? {
        if (lanIp.isNullOrBlank()) return null
        val rewritten = url
            .replace("127.0.0.1", lanIp)
            .replace("localhost", lanIp)
        return if (rewritten == url) null else rewritten
    }

    /**
     * Pick the device's site-local IPv4 address from [addresses] (the
     * dotted-string form of every non-loopback address of every interface),
     * preferring private ranges in the order a phone actually assigns them:
     * `192.168.` (typical Wi-Fi), `10.` (carrier/mobile hotspot), then
     * `172.16.–172.31.`. Null when none matches — the share action hides.
     */
    fun pickSiteLocalIpv4(addresses: List<String>): String? {
        val siteLocal = addresses.filter { isSiteLocalIpv4(it) }
        return siteLocal.firstOrNull { it.startsWith("192.168.") }
            ?: siteLocal.firstOrNull { it.startsWith("10.") }
            ?: siteLocal.firstOrNull { it.startsWith("172.") && isPrivate172(it) }
    }

    /**
     * Every non-loopback IPv4 address of every up interface, as dotted
     * strings. Wrapped so [DesktopWebViewPanel] has one call site and tests
     * never need a real [NetworkInterface].
     */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())

    private fun isSiteLocalIpv4(address: String): Boolean =
        address.contains('.') && !address.startsWith("127.") &&
            (address.startsWith("192.168.") || address.startsWith("10.") || isPrivate172(address))

    private fun isPrivate172(address: String): Boolean {
        if (!address.startsWith("172.")) return false
        val second = address.substringAfter("172.").substringBefore('.').toIntOrNull() ?: return false
        return second in 16..31
    }
}

/**
 * The WebView run mode's surface: a one-row header (URL, share-to-LAN,
 * open-in-browser) over a fullscreen in-app WebView.
 *
 * ## Why the WebView is configured the way it is
 *
 * - `javaScriptEnabled` + `domStorageEnabled`: a guest webtop/noVNC/code-server
 *   page is an application, not a document — without both it renders blank.
 * - No file access, no content access, no universal support: the panel loads
 *   one loopback origin the user chose; it gains nothing from bridging the
 *   Android file system into that origin, so it is not granted.
 *   Pre-release audit fix (2026-09-02, security S7 / desktop C3): the
 *   defaults those doc lines relied on were not enough — `allowFileAccess`
 *   defaults to true on API 28/29 and `allowContentAccess` is true on all
 *   APIs. All of the file/content switches are now set EXPLICITLY false,
 *   and a `WebViewClient` pins navigation to the exact loopback origin this
 *   panel was created with (a guest page cannot steer the WebView to a
 *   remote site or a file: URL).
 * - The header stays visible in every state. Unlike the VNC run mode, whose
 *   fullscreen desktop hides the app bar (§1.138), a web page has its own
 *   chrome and needs the app's back arrow reachable at all times.
 *
 * Back-press: the WebView keeps its own history, but the system back pops the
 * Desktop destination (the screen's contract) — a web session is disposable in
 * a way a VNC session is not, because the guest server keeps running.
 */
@Composable
fun DesktopWebViewPanel(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lanIp = remember { DesktopWebMode.lanAddresses().let { DesktopWebMode.pickSiteLocalIpv4(it) } }
    val shareUrl = remember(url, lanIp) { DesktopWebMode.lanShareUrl(url, lanIp) }
    // Pre-release audit fix (desktop C2): reload arbitration. Comparing
    // `web.url != url` reloaded the ORIGINAL url on every recomposition once
    // the page had redirected or navigated in-app — discarding session state
    // (and looping with a redirecting server). Track "already loaded this
    // url" instead; only a genuine url CHANGE re-loads.
    var loadedUrl by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                enabled = shareUrl != null,
                onClick = {
                    shareUrl?.let { shared ->
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, shared)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(send, null)
                        )
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.desktop_web_share_lan),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    )
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInBrowser,
                    contentDescription = stringResource(R.string.desktop_web_open_browser),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AndroidView(
            factory = { appContext ->
                WebView(appContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // Security S7 (2026-09-02): explicit hard denies — the
                    // API-28/29 default for allowFileAccess is true, and
                    // allowContentAccess defaults to true everywhere. The
                    // from-file URL switches are false by default on modern
                    // targets but are pinned here so the guarantee does not
                    // silently depend on targetSdk defaults.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    // Desktop C3: pin navigation to the loopback origin this
                    // panel was opened for. Everything else (remote https,
                    // file:, content:) is refused — the header's
                    // open-in-browser button is the supported escape hatch.
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val target = request.url
                            val pinned = android.net.Uri.parse(url)
                            val sameOrigin = target.host == pinned.host &&
                                target.port == pinned.port &&
                                (target.scheme == "http" || target.scheme == "https")
                            return !sameOrigin
                        }
                    }
                    loadUrl(url)
                    loadedUrl = url
                }
            },
            update = { web -> if (loadedUrl != url) {
                web.loadUrl(url)
                loadedUrl = url
            } },
            onRelease = { web ->
                // Desktop C3 (2026-09-02): the WebView (and its renderer
                // process) was never destroyed before — every visit to web
                // mode leaked it for the remaining process lifetime.
                web.destroy()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}
