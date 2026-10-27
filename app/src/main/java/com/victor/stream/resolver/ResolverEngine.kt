package com.victor.stream.resolver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.victor.stream.core.FailReason
import com.victor.stream.core.Logger
import com.victor.stream.data.CatalogRepository
import com.victor.stream.domain.ResolvePlan
import com.victor.stream.domain.ResolvedStream
import com.victor.stream.domain.ResolverEvent
import com.victor.stream.domain.ServerCandidate
import com.victor.stream.domain.StreamMimeType
import com.victor.stream.domain.StreamResolver
import com.victor.stream.domain.SubtitleTrack
import com.victor.stream.providers.ProviderRegistry
import com.victor.stream.providers.StreamCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ==========================================
// 1. STREAM URL CLASSIFIER & MIME DETECTOR
// ==========================================

object StreamUrlClassifier {
    private val blockedDomains = listOf(
        "doubleclick.net", "googlesyndication.com", "popads.net", "popcash.net", "adsterra.com",
        "exoclick.com", "propellerads.com", "clickadu.com", "hilltopads.net", "juicyads.com",
        "trafficjunky.com", "yandex.ru", "clarity.ms", "google-analytics.com", "googletagmanager.com",
        "acscdn.com", "onmarshtompor.com", "achcdn.com", "histats.com", "tsyndicate.com",
        "a-ads.com", "bet365", "1xbet", "devtools-console-detect", "disable-devtool",
        "explorads.com"
    )

    private val nonVideoExtensions = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".woff", ".woff2",
        ".ttf", ".ico", ".gif", ".vtt", ".srt", ".ass", ".ts", ".m4s", ".aac", ".html", ".php"
    )

    fun isBlockedDomain(url: String): Boolean {
        val lower = url.lowercase()
        return blockedDomains.any { lower.contains(it) }
    }

    fun isMediaSegmentToBlockInWebView(url: String): Boolean {
        val path = url.lowercase().substringBefore("?")
        return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") || path.endsWith(".mp3")
    }

    fun isSubtitleUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http") || isBlockedDomain(lower)) return false
        if (lower.contains("thumb") || lower.contains("sprite") || lower.contains("preview") ||
            lower.contains("storyboard") || lower.contains("trickle")
        ) return false
        val path = lower.substringBefore("?")
        return path.endsWith(".vtt") || path.endsWith(".srt") || lower.contains(".vtt?") || lower.contains(".srt?")
    }

    fun inferSubtitleLabel(url: String, index: Int): Pair<String, String> {
        val l = url.lowercase()
        return when {
            l.contains("eng") || l.contains("_en") || l.contains("-en") || l.contains("/en.") -> "English" to "en"
            l.contains("spa") || l.contains("_es") || l.contains("spanish") -> "Spanish" to "es"
            l.contains("fre") || l.contains("fra") || l.contains("french") -> "French" to "fr"
            l.contains("ger") || l.contains("deu") || l.contains("german") -> "German" to "de"
            l.contains("por") || l.contains("portuguese") -> "Portuguese" to "pt"
            l.contains("ara") || l.contains("arabic") -> "Arabic" to "ar"
            l.contains("hin") || l.contains("hindi") -> "Hindi" to "hi"
            else -> "English CC #${index + 1}" to "en"
        }
    }

    fun unwrapStreamUrl(rawUrl: String): String = try {
        if (rawUrl.contains("?url=http", true) || rawUrl.contains("&url=http", true)) {
            val decoded = URLDecoder.decode(rawUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
            if (decoded.startsWith("http")) decoded else rawUrl
        } else {
            rawUrl
        }
    } catch (_: Exception) {
        rawUrl
    }

    fun isPlayableVideoStream(
        url: String,
        mimeType: String? = null,
        acceptHeader: String? = null,
        verifiedByContent: Boolean = false
    ): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http") || isBlockedDomain(lower) || isSubtitleUrl(lower)) return false
        if (lower.contains("url=undefined") || lower.endsWith("undefined")) return false
        // Reject DASH manifests: VidLink's .mpd streams consistently fail playback with
        // ERROR_CODE_IO_BAD_HTTP_STATUS. Skipping them makes the resolver keep looking for
        // a playable HLS (.m3u8) / MP4 stream instead of handing the player a dead manifest.
        if (lower.contains(".mpd") || lower.contains("/dash/")) return false
        if (verifiedByContent) return true
        val pathPart = lower.substringBefore("?")
        if (nonVideoExtensions.any { pathPart.endsWith(it) }) return false
        if (lower.contains("favicon") || lower.contains("/ads/") || lower.contains("preroll")) return false

        val mime = mimeType?.lowercase().orEmpty()
        if (mime.contains("mpegurl") || mime.contains("application/vnd.apple.mpegurl") ||
            mime.contains("video/mp4")
        ) return true

        return lower.contains(".m3u8") ||
                pathPart.endsWith(".mp4") ||
                lower.contains(".mp4?") ||
                lower.contains("get_video?id=") ||
                lower.contains("/hls/") ||
                lower.contains("/hls2/") ||
                lower.contains("master.txt") ||
                lower.contains("playlist.m3u8") ||
                lower.contains("index.m3u8") ||
                lower.contains("index.txt") ||
                lower.contains(".urlset/") ||
                lower.contains("/manifests/") ||
                lower.contains(".workers.dev/") ||
                (lower.contains("/stream/") && acceptHeader?.lowercase()?.contains("video") == true)
    }

    fun detectStreamMimeType(url: String, contentTypeHeader: String? = null): StreamMimeType {
        val lowerUrl = url.lowercase()
        val pathPart = lowerUrl.substringBefore("?")
        val cType = contentTypeHeader?.lowercase().orEmpty()

        return when {
            cType.contains("mpegurl") ||
                    pathPart.endsWith(".m3u8") ||
                    lowerUrl.contains(".m3u8") ||
                    lowerUrl.contains("/hls/") ||
                    lowerUrl.contains("/hls2/") ||
                    lowerUrl.contains("master.txt") ||
                    lowerUrl.contains("index.txt") ||
                    lowerUrl.contains(".urlset/") ||
                    lowerUrl.contains("/manifests/") -> StreamMimeType.HLS

            cType.contains("dash+xml") ||
                    pathPart.endsWith(".mpd") ||
                    lowerUrl.contains(".mpd") ||
                    lowerUrl.contains("/dash/") -> StreamMimeType.DASH

            cType.contains("video/mp4") ||
                    pathPart.endsWith(".mp4") ||
                    lowerUrl.contains(".mp4?") ||
                    lowerUrl.contains("get_video?id=") -> StreamMimeType.MP4

            else -> StreamMimeType.UNKNOWN
        }
    }
}

// ==========================================
// 2. SNIFFER SCRIPT (Injected JS)
// ==========================================

object SnifferScript {
    val SCRIPT: String = """
        (function() {
            try {
                if (!window.__snifferHooked) {
                    window.__snifferHooked = true;
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function() {
                            var reqArg = arguments[0];
                            var reqUrl = (typeof reqArg === 'string') ? reqArg : (reqArg && reqArg.url ? reqArg.url : '');
                            return origFetch.apply(this, arguments).then(function(res) {
                                try {
                                    var finalU = res.url || reqUrl || '';
                                    var cType = (res.headers && res.headers.get) ? (res.headers.get('content-type') || '') : '';
                                    if (finalU.indexOf('http') === 0 && finalU.indexOf('undefined') === -1) {
                                        if (finalU.indexOf('.m3u8') !== -1 || finalU.indexOf('.mpd') !== -1 || finalU.indexOf('.mp4') !== -1 || finalU.indexOf('get_video?id=') !== -1 || cType.indexOf('mpegurl') !== -1 || cType.indexOf('dash+xml') !== -1) {
                                            if (window.AndroidSniffer) window.AndroidSniffer.reportStream(finalU, location.href, false);
                                        } else {
                                            res.clone().text().then(function(txt) {
                                                if (!txt) return;
                                                var t = txt.trim();
                                                if ((t.indexOf('#EXTM3U') === 0 || t.indexOf('<MPD') !== -1) && window.AndroidSniffer) {
                                                    window.AndroidSniffer.reportStream(finalU, location.href, true);
                                                } else if (t.indexOf('.m3u8') !== -1 || t.indexOf('.mpd') !== -1) {
                                                    var m = t.match(/https?:\\?\/\\?\/[^"'\s<>]+\.(?:m3u8|mpd)[^"'\s<>]*/i);
                                                    if (m && m[0] && window.AndroidSniffer) {
                                                        window.AndroidSniffer.reportStream(m[0].replace(/\\\//g, '/'), location.href, false);
                                                    }
                                                }
                                                if (t.indexOf('.vtt') !== -1 || t.indexOf('.srt') !== -1) {
                                                    var subM = t.match(/https?:\\?\/\\?\/[^"'\s<>]+\.(?:vtt|srt)[^"'\s<>]*/gi);
                                                    if (subM && window.AndroidSniffer) {
                                                        for (var i = 0; i < subM.length; i++) {
                                                            window.AndroidSniffer.reportSubtitle(subM[i].replace(/\\\//g, '/'), 'English');
                                                        }
                                                    }
                                                }
                                            }).catch(function(){});
                                        }
                                    }
                                } catch(e) {}
                                return res;
                            });
                        };
                    }
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__reqUrl = url;
                        this.addEventListener('load', function() {
                            try {
                                var rUrl = this.responseURL || this.__reqUrl || '';
                                if (rUrl.indexOf('http') === 0 && rUrl.indexOf('undefined') === -1) {
                                    var body = (typeof this.responseText === 'string') ? this.responseText.trim() : '';
                                    var isManifest = body.indexOf('#EXTM3U') === 0 || body.indexOf('<MPD') !== -1;
                                    if (rUrl.indexOf('.m3u8') !== -1 || rUrl.indexOf('.mpd') !== -1 || isManifest) {
                                        if (window.AndroidSniffer) window.AndroidSniffer.reportStream(rUrl, location.href, isManifest);
                                    } else if (body.indexOf('.m3u8') !== -1 || body.indexOf('.mpd') !== -1) {
                                        var m = body.match(/https?:\\?\/\\?\/[^"'\s<>]+\.(?:m3u8|mpd)[^"'\s<>]*/i);
                                        if (m && m[0] && window.AndroidSniffer) {
                                            window.AndroidSniffer.reportStream(m[0].replace(/\\\//g, '/'), location.href, false);
                                        }
                                    }
                                }
                            } catch(e) {}
                        });
                        return origOpen.apply(this, arguments);
                    };
                }

                var robot = document.getElementById('robotlink') || document.getElementById('ideoolink') || document.getElementById('norobotlink');
                if (robot && robot.innerText && robot.innerText.indexOf('get_video') !== -1) {
                    var stUrl = robot.innerText.trim();
                    if (stUrl.indexOf('//') === 0) stUrl = 'https:' + stUrl;
                    else if (stUrl.indexOf('/') === 0) stUrl = location.origin + stUrl;
                    if (stUrl.indexOf('http') === 0 && window.AndroidSniffer) {
                        window.AndroidSniffer.reportStream(stUrl + '&stream=1', location.href, false);
                    }
                }

                var scripts = document.querySelectorAll('script');
                for (var s = 0; s < scripts.length; s++) {
                    var sc = scripts[s].innerHTML || '';
                    if (sc.indexOf('.m3u8') !== -1 || sc.indexOf('.mpd') !== -1 || sc.indexOf('.mp4') !== -1 || sc.indexOf('hls') !== -1) {
                        var hlsMatch = sc.match(/https?:\\?\/\\?\/[^"'\s<>]+\.(?:m3u8|mpd|mp4)[^"'\s<>]*/i);
                        if (hlsMatch && hlsMatch[0] && window.AndroidSniffer) {
                            var candidate = hlsMatch[0].replace(/\\\//g, '/');
                            if (candidate.indexOf('undefined') === -1) {
                                window.AndroidSniffer.reportStream(candidate, location.href, false);
                            }
                        }
                    }
                }

                var videos = document.querySelectorAll('video, audio');
                for (var i = 0; i < videos.length; i++) {
                    videos[i].muted = true;
                    videos[i].volume = 0;
                    var vSrc = videos[i].currentSrc || videos[i].src || '';
                    if (vSrc && vSrc.indexOf('http') === 0 && vSrc.indexOf('blob:') === -1 && window.AndroidSniffer) {
                        window.AndroidSniffer.reportStream(vSrc, location.href, false);
                    }
                    try { if (videos[i].paused) videos[i].play().catch(function(){}); } catch(e) {}
                }

                var iframes = document.querySelectorAll('iframe');
                for (var k = 0; k < iframes.length; k++) {
                    var fSrc = iframes[k].src || iframes[k].getAttribute('data-src') || iframes[k].getAttribute('data-lazy-src') || '';
                    if (fSrc && fSrc.indexOf('//') === 0) fSrc = 'https:' + fSrc;
                    else if (fSrc && fSrc.indexOf('/') === 0) fSrc = location.origin + fSrc;
                    if (fSrc && fSrc.indexOf('http') === 0 && fSrc.toLowerCase().indexOf('google') === -1 && fSrc.toLowerCase().indexOf('cloudflare') === -1 && fSrc.toLowerCase().indexOf('youtube') === -1 && fSrc.toLowerCase().indexOf('about:blank') === -1) {
                        if (window.AndroidSniffer) window.AndroidSniffer.reportIframe(fSrc, location.href);
                    }
                }

                var clickables = document.querySelectorAll('button, a, div[role="button"], .play, .btn-play, .jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid, #play-button, .play-btn');
                for (var c = 0; c < clickables.length; c++) {
                    var el = clickables[c];
                    var label = (el.innerText || el.getAttribute('aria-label') || el.className || el.id || '').toLowerCase();
                    if (label === 'play' || label.indexOf('watch free') !== -1 || label.indexOf('play now') !== -1 || label.indexOf('jw-icon-display') !== -1 || label.indexOf('vjs-big-play-button') !== -1 || label.indexOf('plyr__control') !== -1) {
                        el.click();
                    }
                }
            } catch(err) {}
        })();
    """.trimIndent()
}

// ==========================================
// 3. WEBVIEW HOST (Patches A, B, C from Fix1.docx)
// ==========================================

@SuppressLint("SetJavaScriptEnabled")
class WebViewHost(context: Context) {
    private val appContext = context.applicationContext
    val mainHandler = Handler(Looper.getMainLooper())
    private var attachedActivityRef: WeakReference<Activity>? = null

    private val containerLayout = FrameLayout(appContext).apply {
        layoutParams = FrameLayout.LayoutParams(1, 1)
        alpha = 0.01f
        visibility = View.VISIBLE
    }

    private var primaryWebView: WebView? = null
    private val popupWebViews = CopyOnWriteArrayList<WebView>()
    private val activeGeneration = AtomicInteger(0)

    @Volatile var isDestroyed: Boolean = false
        private set

    var activeUserAgent: String =
        "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private set

    var onIframeDiscovered: ((generation: Int, iframeUrl: String, parentUrl: String?) -> Unit)? = null
    var onSubtitleDiscovered: ((generation: Int, subUrl: String, label: String?) -> Unit)? = null
    var onStreamCaptured: ((generation: Int, streamUrl: String, headers: Map<String, String>, fallbackReferer: String?, verifiedByContent: Boolean) -> Unit)? = null
    var onPageStartedCallback: ((generation: Int, url: String) -> Unit)? = null

    fun attachToActivity(activity: Activity) {
        mainHandler.post {
            try {
                attachedActivityRef = WeakReference(activity)
                val decorGroup = activity.window?.decorView as? ViewGroup ?: return@post
                (containerLayout.parent as? ViewGroup)?.let { parent ->
                    if (parent != decorGroup) {
                        parent.removeView(containerLayout)
                    }
                }
                if (containerLayout.parent == null) {
                    decorGroup.addView(containerLayout, FrameLayout.LayoutParams(1, 1))
                }
            } catch (e: Exception) {
                Logger.w("WebViewHost", "Could not attach containerLayout to activity", e)
            }
        }
    }

    private val automationRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            primaryWebView?.let { safeEvaluateJs(it, SnifferScript.SCRIPT) }
            popupWebViews.forEach { popup -> safeEvaluateJs(popup, SnifferScript.SCRIPT) }
            mainHandler.postDelayed(this, 600L)
        }
    }

    private inner class JsBridge(private val boundGeneration: Int) {
        @JavascriptInterface
        fun reportIframe(iframeUrl: String?, parentUrl: String?) {
            if (iframeUrl.isNullOrBlank() || !iframeUrl.startsWith("http") || StreamUrlClassifier.isBlockedDomain(iframeUrl)) return
            mainHandler.post {
                if (!isDestroyed && boundGeneration == activeGeneration.get()) {
                    onIframeDiscovered?.invoke(boundGeneration, iframeUrl.trim(), parentUrl)
                }
            }
        }

        @JavascriptInterface
        fun reportSubtitle(subUrl: String?, label: String?) {
            if (subUrl.isNullOrBlank() || !subUrl.startsWith("http") || StreamUrlClassifier.isBlockedDomain(subUrl)) return
            mainHandler.post {
                if (!isDestroyed && boundGeneration == activeGeneration.get()) {
                    onSubtitleDiscovered?.invoke(boundGeneration, subUrl.trim(), label)
                }
            }
        }

        @JavascriptInterface
        fun reportStream(streamUrl: String?, refererUrl: String?, verifiedByContent: Boolean) {
            if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http") ||
                streamUrl.contains("undefined", true) ||
                StreamUrlClassifier.isBlockedDomain(streamUrl) ||
                StreamUrlClassifier.isMediaSegmentToBlockInWebView(streamUrl)
            ) return
            mainHandler.post {
                if (!isDestroyed && boundGeneration == activeGeneration.get()) {
                    onStreamCaptured?.invoke(boundGeneration, streamUrl.trim(), emptyMap(), refererUrl, verifiedByContent)
                }
            }
        }
    }

    // PATCH A from Fix1.docx: One unified SniffClient used by BOTH primary WebView and every popup WebView
    private inner class SniffClient(
        private val isPopup: Boolean,
        private val boundGeneration: Int
    ) : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (isDestroyed || boundGeneration != activeGeneration.get()) return
            if (!isPopup) {
                onPageStartedCallback?.invoke(boundGeneration, url.orEmpty())
            } else {
                Logger.d("WebViewHost", "Popup onPageStarted: $url")
            }
            if (!url.isNullOrBlank() && StreamUrlClassifier.isPlayableVideoStream(url)) {
                reportCapture(url, view)
            }
            view?.let { safeEvaluateJs(it, SnifferScript.SCRIPT) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (isDestroyed || boundGeneration != activeGeneration.get()) return
            view?.let { safeEvaluateJs(it, SnifferScript.SCRIPT) }
            if (!isPopup) {
                mainHandler.removeCallbacks(automationRunnable)
                mainHandler.postDelayed(automationRunnable, 250L)
            } else {
                Logger.d("WebViewHost", "Popup onPageFinished: $url")
                listOf(600L, 1_500L, 3_000L).forEach { delayMs ->
                    mainHandler.postDelayed({
                        if (!isDestroyed && boundGeneration == activeGeneration.get()) {
                            view?.let { v -> safeEvaluateJs(v, SnifferScript.SCRIPT) }
                        }
                    }, delayMs)
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url?.toString().orEmpty()
            if (!u.startsWith("http")) return true
            if (StreamUrlClassifier.isBlockedDomain(u)) {
                return true // Swallow ad hops
            }
            if (StreamUrlClassifier.isPlayableVideoStream(u)) {
                reportCapture(u, view)
            }
            // Return false so the popup or main WebView ACTUALLY loads the player page!
            return false
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Logger.w("WebViewHost", "MainFrame error (isPopup=$isPopup, code=${error?.errorCode}): ${error?.description} on ${request.url}")
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val reqUrl = request?.url?.toString() ?: return null
            val genSnap = activeGeneration.get()
            if (boundGeneration != genSnap) {
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            Logger.logSubresourceReq(reqUrl)

            if (StreamUrlClassifier.isBlockedDomain(reqUrl)) {
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            if (StreamUrlClassifier.isMediaSegmentToBlockInWebView(reqUrl)) {
                val h = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                    "Access-Control-Allow-Headers" to "*"
                )
                return WebResourceResponse("text/plain", "UTF-8", 200, "OK", h, ByteArrayInputStream(ByteArray(0)))
            }
            if (StreamUrlClassifier.isSubtitleUrl(reqUrl)) {
                mainHandler.post {
                    if (!isDestroyed && boundGeneration == activeGeneration.get()) {
                        onSubtitleDiscovered?.invoke(boundGeneration, reqUrl, null)
                    }
                }
            }

            val reqHeaders = request.requestHeaders ?: emptyMap()
            val acceptHeader = reqHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value
            if (StreamUrlClassifier.isPlayableVideoStream(reqUrl, null, acceptHeader, false)) {
                val headersCopy = HashMap(reqHeaders)
                mainHandler.post {
                    if (!isDestroyed && boundGeneration == activeGeneration.get()) {
                        val liveViewUrl = try { view?.url } catch (_: Exception) { null }
                        onStreamCaptured?.invoke(boundGeneration, reqUrl, headersCopy, liveViewUrl, false)
                    }
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        private fun reportCapture(url: String, view: WebView?) {
            mainHandler.post {
                if (!isDestroyed && boundGeneration == activeGeneration.get()) {
                    val liveViewUrl = try { view?.url } catch (_: Exception) { null }
                    onStreamCaptured?.invoke(boundGeneration, url, emptyMap(), liveViewUrl, false)
                }
            }
        }
    }

    // PATCH C from Fix1.docx: Shared WebChromeClient that creates full sniffing popups and doesn't kill them at 250ms
    private val sniffChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (isDestroyed) return false
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val gen = activeGeneration.get()
            val popupWv = buildWebView(isPopup = true, generation = gen)
            Logger.d("WebViewHost", "onCreateWindow dialog=$isDialog gesture=$isUserGesture parent=${view?.url}")
            popupWebViews.add(popupWv)
            if (popupWebViews.size > 6) {
                safeDestroyPopupLater(popupWebViews.removeAt(0), 0L)
            }
            containerLayout.addView(popupWv)
            transport.webView = popupWv
            resultMsg.sendToTarget()
            // 9,000ms backstop only — silenceAndStopOnMain() reaps popups cleanly when the candidate finishes
            safeDestroyPopupLater(popupWv, 9_000L)
            return true
        }

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            Logger.throttledD("WebConsole", msg.sourceId().orEmpty().takeLast(24), 2_500L) {
                "JS[${msg.messageLevel()}] ${msg.message()}"
            }
            return true
        }
    }

    // PATCH B from Fix1.docx: Unified WebView factory used by both primary and popup WebViews
    @SuppressLint("JavascriptInterface")
    private fun buildWebView(isPopup: Boolean, generation: Int): WebView = WebView(appContext).apply {
        layoutParams = FrameLayout.LayoutParams(1, 1)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(this, true)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadsImagesAutomatically = true
            blockNetworkImage = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            val cleanedUa = (userAgentString ?: activeUserAgent)
                .replace("; wv)", ")")
                .replace(Regex("Version/[0-9.]+\\s"), "")
            userAgentString = cleanedUa
            activeUserAgent = cleanedUa
        }
        addJavascriptInterface(JsBridge(generation), "AndroidSniffer")
        webViewClient = SniffClient(isPopup = isPopup, boundGeneration = generation)
        webChromeClient = sniffChromeClient
    }

    private fun ensurePrimaryWebViewOnMain(generation: Int): WebView {
        isDestroyed = false
        attachedActivityRef?.get()?.let { act ->
            val decor = act.window?.decorView as? ViewGroup
            if (decor != null && containerLayout.parent == null) {
                try {
                    decor.addView(containerLayout, FrameLayout.LayoutParams(1, 1))
                } catch (_: Exception) {}
            }
        }

        val existing = primaryWebView
        if (existing != null) {
            existing.removeJavascriptInterface("AndroidSniffer")
            existing.addJavascriptInterface(JsBridge(generation), "AndroidSniffer")
            existing.webViewClient = SniffClient(isPopup = false, boundGeneration = generation)
            existing.webChromeClient = sniffChromeClient
            return existing
        }

        val wv = buildWebView(isPopup = false, generation = generation)
        containerLayout.removeAllViews()
        containerLayout.addView(wv)
        primaryWebView = wv
        return wv
    }

    private fun safeDestroyPopupLater(popup: WebView, delayMs: Long = 200L) {
        mainHandler.postDelayed({
            try {
                if (popupWebViews.remove(popup)) {
                    popup.stopLoading()
                    containerLayout.removeView(popup)
                    popup.destroy()
                }
            } catch (_: Exception) {}
        }, delayMs)
    }

    private fun safeEvaluateJs(targetView: WebView, script: String) {
        if (isDestroyed) return
        try {
            targetView.evaluateJavascript(script, null)
        } catch (_: Exception) {}
    }

    fun loadCandidateOnMain(generation: Int, url: String, headers: Map<String, String>) {
        activeGeneration.set(generation)
        mainHandler.post {
            // Reap any leftover popups from the previous candidate so they cannot contaminate this candidate
            popupWebViews.toList().forEach { popup ->
                safeDestroyPopupLater(popup, 0L)
            }
            val wv = ensurePrimaryWebViewOnMain(generation)
            try {
                wv.onResume()
                wv.resumeTimers()
                mainHandler.removeCallbacks(automationRunnable)
                wv.stopLoading()
                if (headers.isEmpty()) {
                    wv.loadUrl(url)
                } else {
                    wv.loadUrl(url, headers)
                }
            } catch (e: Exception) {
                Logger.w("WebViewHost", "Failed to loadUrl=$url", e)
            }
        }
    }

    fun silenceAndStopOnMain() {
        activeGeneration.incrementAndGet()
        mainHandler.post {
            mainHandler.removeCallbacks(automationRunnable)
            popupWebViews.toList().forEach { popup ->
                safeDestroyPopupLater(popup, 0L)
            }
            val wv = primaryWebView ?: return@post
            if (!isDestroyed) {
                try {
                    wv.evaluateJavascript("document.querySelectorAll('video,audio').forEach(e=>{e.pause();e.src='';e.load();});", null)
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                } catch (_: Exception) {}
            }
        }
    }
}

// ==========================================
// 4. WEBVIEW STREAM RESOLVER (Patches D & E from Fix1.docx)
// ==========================================

class WebViewStreamResolver(
    context: Context,
    private val providerRegistry: ProviderRegistry,
    private val streamCache: StreamCache,
    private val catalogRepository: CatalogRepository
) : StreamResolver {

    private val tag = "StreamResolver"
    val webViewHost = WebViewHost(context)
    private val resolveMutex = Mutex()
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generationCounter = AtomicInteger(100)

    @Volatile private var activeResolveJob: Job? = null

    fun attachHostToActivity(activity: Activity) {
        webViewHost.attachToActivity(activity)
    }

    override fun cancelActive() {
        activeResolveJob?.cancel()
        webViewHost.silenceAndStopOnMain()
    }

    override fun resolve(plan: ResolvePlan, harvestAll: Boolean): Flow<ResolverEvent> = callbackFlow {
        val job = resolverScope.launch {
            resolveMutex.withLock {
                val queue = CopyOnWriteArrayList<ServerCandidate>(plan.candidates)
                val capturedSubs = CopyOnWriteArrayList<SubtitleTrack>(
                    streamCache.getSubtitles(
                        plan.media.id,
                        plan.episode?.seasonNumber,
                        plan.episode?.episodeNumber
                    )
                )

                launch(Dispatchers.IO) {
                    val wyzie = catalogRepository.fetchSubtitlesForMedia(plan.media, plan.episode)
                    if (wyzie.isNotEmpty()) {
                        streamCache.putSubtitles(
                            plan.media.id,
                            plan.episode?.seasonNumber,
                            plan.episode?.episodeNumber,
                            wyzie
                        )
                        wyzie.forEach { sub ->
                            if (capturedSubs.none { it.url == sub.url }) capturedSubs.add(sub)
                        }
                    }
                }

                val firstSent = AtomicBoolean(false)
                val currentCandidateResolved = AtomicBoolean(false)
                val childIframeQueuedForCurrent = AtomicBoolean(false)

                // PATCH D from Fix1.docx: Defer early preroll captures instead of dropping them forever
                var pendingPreroll: ResolvedStream? = null
                var pendingPrerollAtMs = 0L

                var currentIndex = 0
                var currentGeneration = 0
                var pageStartedAtMs = 0L
                val triedProviders = mutableListOf<String>()

                fun buildResolved(
                    activeCandidate: ServerCandidate,
                    unwrappedUrl: String,
                    chromiumHeaders: Map<String, String>,
                    fallbackReferer: String?
                ): ResolvedStream {
                    val finalHeaders = buildStreamHeaders(
                        activeCandidate = activeCandidate,
                        chromiumHeaders = chromiumHeaders,
                        fallbackReferer = fallbackReferer,
                        streamUrl = unwrappedUrl,
                        userAgent = webViewHost.activeUserAgent
                    )
                    val contentType = chromiumHeaders.entries.firstOrNull {
                        it.key.equals("Content-Type", true)
                    }?.value
                    val detectedMime = StreamUrlClassifier.detectStreamMimeType(unwrappedUrl, contentType)
                    val enrichedLabel = providerRegistry.enrichHostLabel(unwrappedUrl, activeCandidate.label)

                    return ResolvedStream(
                        streamUrl = unwrappedUrl,
                        mimeType = detectedMime,
                        headers = finalHeaders,
                        subtitles = capturedSubs.distinctBy { it.url },
                        providerId = activeCandidate.providerId,
                        serverLabel = enrichedLabel,
                        episode = plan.episode
                    )
                }

                fun emitResolved(resolved: ResolvedStream) {
                    streamCache.put(
                        mediaId = plan.media.id,
                        season = plan.episode?.seasonNumber,
                        episode = plan.episode?.episodeNumber,
                        stream = resolved
                    )
                    providerRegistry.recordOutcome(resolved.providerId, success = true)
                    Logger.i(tag, "CAPTURED stream=${resolved.streamUrl} mime=${resolved.mimeType} via=${resolved.serverLabel}")

                    currentCandidateResolved.set(true)
                    if (!harvestAll) {
                        if (firstSent.compareAndSet(false, true)) {
                            webViewHost.silenceAndStopOnMain()
                            trySend(ResolverEvent.Captured(resolved))
                        }
                    } else {
                        trySend(ResolverEvent.Captured(resolved))
                    }
                }

                webViewHost.onPageStartedCallback = { gen, _ ->
                    if (gen == currentGeneration) {
                        pageStartedAtMs = System.currentTimeMillis()
                    }
                }

                webViewHost.onSubtitleDiscovered = { gen, subUrl, label ->
                    if (gen == currentGeneration && capturedSubs.none { it.url == subUrl }) {
                        val (inferredLabel, lang) = StreamUrlClassifier.inferSubtitleLabel(subUrl, capturedSubs.size)
                        val fmt = if (subUrl.contains(".vtt", true)) "vtt" else "srt"
                        val track = SubtitleTrack(subUrl, label?.takeIf { it.isNotBlank() } ?: inferredLabel, lang, fmt)
                        capturedSubs.add(0, track)
                        streamCache.putSubtitles(
                            plan.media.id,
                            plan.episode?.seasonNumber,
                            plan.episode?.episodeNumber,
                            capturedSubs.toList()
                        )
                    }
                }

                webViewHost.onIframeDiscovered = { gen, iframeUrl, parentUrl ->
                    if (gen == currentGeneration) {
                        val currentCandidate = queue.getOrNull(currentIndex)
                        if (currentCandidate != null && queue.none { it.embedUrl == iframeUrl } && queue.size < 20) {
                            val enrichedLabel = providerRegistry.enrichHostLabel(iframeUrl, currentCandidate.label)
                            val childHeaders = HashMap(currentCandidate.headers)
                            if (!currentCandidate.omitReferer) {
                                val ref = parentUrl?.takeIf { it.startsWith("http") } ?: currentCandidate.embedUrl
                                childHeaders["Referer"] = ref
                            }
                            Logger.d(tag, "Discovered child player iframe: $iframeUrl (parent=${currentCandidate.label})")
                            queue.add(
                                currentIndex + 1,
                                currentCandidate.copy(
                                    label = enrichedLabel,
                                    embedUrl = iframeUrl,
                                    headers = childHeaders
                                )
                            )
                            childIframeQueuedForCurrent.set(true)
                        }
                    }
                }

                webViewHost.onStreamCaptured = { gen, rawStreamUrl, chromiumHeaders, fallbackReferer, verifiedByContent ->
                    if (gen == currentGeneration) {
                        val activeCandidate = queue.getOrNull(currentIndex)
                        if (activeCandidate != null) {
                            val unwrapped = StreamUrlClassifier.unwrapStreamUrl(rawStreamUrl)
                            if (StreamUrlClassifier.isPlayableVideoStream(unwrapped, null, null, verifiedByContent)) {
                                val sinceStart = System.currentTimeMillis() - pageStartedAtMs
                                val skipAsPreroll = pageStartedAtMs > 0L &&
                                        sinceStart in 1..600L &&
                                        !verifiedByContent &&
                                        !activeCandidate.isInstantTrusted

                                // PATCH D from Fix1.docx
                                if (skipAsPreroll) {
                                    if (pendingPreroll == null) {
                                        Logger.d(tag, "Deferring early stream (${sinceStart}ms) for settle check: $unwrapped")
                                        pendingPreroll = buildResolved(activeCandidate, unwrapped, chromiumHeaders, fallbackReferer)
                                        pendingPrerollAtMs = System.currentTimeMillis()
                                    }
                                } else {
                                    pendingPreroll = null
                                    emitResolved(buildResolved(activeCandidate, unwrapped, chromiumHeaders, fallbackReferer))
                                }
                            }
                        }
                    }
                }

                // Single Advance Authority Loop
                while (currentIndex < queue.size) {
                    if (!harvestAll && firstSent.get()) break

                    val candidate = queue[currentIndex]
                    triedProviders.add(candidate.providerId)
                    currentCandidateResolved.set(false)
                    childIframeQueuedForCurrent.set(false)
                    pendingPreroll = null
                    pendingPrerollAtMs = 0L

                    val preCached = streamCache.get(
                        plan.media.id,
                        plan.episode?.seasonNumber,
                        plan.episode?.episodeNumber,
                        candidate.providerId
                    )
                    if (preCached != null) {
                        Logger.d(tag, "Cache hit for candidate=${candidate.label}")
                        if (!harvestAll) {
                            if (firstSent.compareAndSet(false, true)) {
                                trySend(ResolverEvent.Captured(preCached))
                                break
                            }
                        } else {
                            trySend(ResolverEvent.Captured(preCached))
                            currentIndex++
                            continue
                        }
                    }

                    trySend(ResolverEvent.Progress(currentIndex + 1, queue.size, candidate.label))
                    Logger.d(tag, "Resolving [${currentIndex + 1}/${queue.size}]: ${candidate.label} -> ${candidate.embedUrl}")

                    currentGeneration = generationCounter.incrementAndGet()
                    pageStartedAtMs = System.currentTimeMillis()
                    webViewHost.loadCandidateOnMain(currentGeneration, candidate.embedUrl, candidate.headers)

                    val startMs = System.currentTimeMillis()
                    val timeoutMs = candidate.timeoutMs.coerceIn(6_500L, 9_000L)

                    while (System.currentTimeMillis() - startMs < timeoutMs) {
                        if (!harvestAll && firstSent.get()) break
                        if (currentCandidateResolved.get()) {
                            if (!harvestAll) break
                            if (System.currentTimeMillis() - startMs >= 1_200L) break
                        }
                        if (childIframeQueuedForCurrent.get() && System.currentTimeMillis() - startMs >= 2_200L) {
                            Logger.d(tag, "Hopping immediately into discovered child iframe for ${candidate.label}")
                            break
                        }
                        // PATCH D from Fix1.docx: Accept pendingPreroll after 1,400ms if no newer manifest replaced it
                        val pending = pendingPreroll
                        if (pending != null && System.currentTimeMillis() - pendingPrerollAtMs > 1_400L && !firstSent.get()) {
                            Logger.d(tag, "Preroll window closed with no better capture — accepting ${pending.streamUrl}")
                            pendingPreroll = null
                            emitResolved(pending)
                            break
                        }
                        delay(150L)
                    }

                    // Final check if a pendingPreroll is still waiting when the loop ends
                    val leftoverPending = pendingPreroll
                    if (leftoverPending != null && !currentCandidateResolved.get() && !firstSent.get()) {
                        pendingPreroll = null
                        emitResolved(leftoverPending)
                    }

                    if (!currentCandidateResolved.get() && !childIframeQueuedForCurrent.get() && !firstSent.get()) {
                        Logger.w(tag, "Candidate timed out after ${timeoutMs}ms: ${candidate.label}")
                        providerRegistry.recordOutcome(candidate.providerId, success = false)
                        trySend(ResolverEvent.CandidateFailed(candidate.providerId, FailReason.TIMEOUT))
                    }

                    currentIndex++
                }

                webViewHost.silenceAndStopOnMain()
                if (!harvestAll && !firstSent.get()) {
                    trySend(ResolverEvent.AllExhausted(triedProviders.distinct()))
                }
                close()
            }
        }
        activeResolveJob = job
        awaitClose {
            job.cancel()
            webViewHost.silenceAndStopOnMain()
        }
    }

    // PATCH E from Fix1.docx: Prioritize live page / popup fallbackReferer over static parent candidate Referer
    private fun buildStreamHeaders(
        activeCandidate: ServerCandidate,
        chromiumHeaders: Map<String, String>,
        fallbackReferer: String?,
        streamUrl: String,
        userAgent: String
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        chromiumHeaders.forEach { (k, v) ->
            if (!k.equals("Range", true) &&
                !k.equals("If-Modified-Since", true) &&
                !k.equals("If-None-Match", true) &&
                !k.equals("X-Requested-With", true)
            ) {
                if (!(activeCandidate.omitReferer && (k.equals("Referer", true) || k.equals("Origin", true)))) {
                    result[k] = v
                }
            }
        }

        if (!activeCandidate.omitReferer) {
            val referer = chromiumHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.takeIf { it.isNotBlank() }
                ?: fallbackReferer?.takeIf { it.startsWith("http") }
                ?: activeCandidate.headers["Referer"]
                ?: activeCandidate.embedUrl

            if (referer.isNotBlank()) {
                result["Referer"] = referer
                if (result.keys.none { it.equals("Origin", true) }) {
                    val origin = try {
                        val u = URI(referer)
                        if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else null
                    } catch (_: Exception) {
                        null
                    } ?: activeCandidate.headers["Origin"]

                    if (!origin.isNullOrBlank()) {
                        result["Origin"] = origin
                    }
                }
            }
        }

        result["User-Agent"] = activeCandidate.headers["User-Agent"] ?: userAgent

        val cookieStr = CookieManager.getInstance().getCookie(streamUrl)
            ?: fallbackReferer?.let { CookieManager.getInstance().getCookie(it) }
            ?: CookieManager.getInstance().getCookie(activeCandidate.embedUrl)
        if (!cookieStr.isNullOrBlank() && result.keys.none { it.equals("Cookie", true) }) {
            result["Cookie"] = cookieStr
        }

        return result
    }
}