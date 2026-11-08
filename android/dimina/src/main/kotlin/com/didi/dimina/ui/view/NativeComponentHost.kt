package com.didi.dimina.ui.view

import com.didi.dimina.ui.view.nativecomponent.NativeComponentBackend
import com.didi.dimina.ui.view.nativecomponent.NativeComponentBackends
import com.didi.dimina.ui.view.nativecomponent.NativeComponentBackendContext
import com.didi.dimina.ui.view.nativecomponent.NativeComponentLayout
import com.didi.dimina.map.MapProviders
import com.didi.dimina.map.MapInstance
import com.didi.dimina.map.MapEvents
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import coil.imageLoader
import coil.request.ImageRequest
import com.didi.dimina.common.LogUtils
import com.didi.dimina.common.PathUtils
import com.didi.dimina.ui.container.DiminaActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Routes component APIs to SDK instances; presentation is delegated to a per-page backend.
 */
class NativeComponentHost(
    private val activity: DiminaActivity,
    private val webView: WebView,
    private val backend: NativeComponentBackend,
    private val embeddedMessageHandler: (JSONObject) -> Unit = {},
) {
    // Preserve the existing container constructor and its trailing message-handler lambda.
    constructor(activity: DiminaActivity, webView: WebView, overlay: FrameLayout,
        embeddedMessageHandler: (JSONObject) -> Unit = {}) : this(activity, webView,
        NativeComponentBackends.create(NativeComponentBackendContext(activity, webView, overlay)), embeddedMessageHandler)

    val capabilities get() = backend.capabilities
    fun updatePageBackgroundColor(color: Int) {
        activity.runOnUiThread { if (!cleared) backend.updatePageBackgroundColor(color) }
    }
    private val components = mutableMapOf<String, NativeComponent>()
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cleared = false

    fun handle(apiName: String, params: JSONObject): Boolean {
        if (cleared) return false
        val type = params.optString("type", VIDEO_TYPE)
        if (type !in SUPPORTED_TYPES) {
            LogUtils.d(TAG, "Ignore unsupported native component: $type")
            return false
        }

        val id = params.optString("id")
        if (id.isEmpty()) {
            LogUtils.e(TAG, "Native component id is empty: $params")
            return true
        }

        activity.runOnUiThread {
            if (cleared) return@runOnUiThread
            if (type == "native/map") {
                handleMap(apiName, id, params)
                return@runOnUiThread
            }
            when (apiName) {
                "componentMount" -> mountComponent(type, id, params)
                "propsUpdate" -> updateComponent(type, id, params)
                "componentUnmount" -> unmountComponent(id)
                "videoContext" -> (components[id] as? NativeVideoComponent)?.handleCommand(params)
            }
        }
        return true
    }

    fun dispatchTouchFromWeb(params: JSONObject): Boolean {
        val targetId = params.optString("targetId")
        if (targetId.isEmpty()) {
            return false
        }
        activity.runOnUiThread {
            if (!cleared) backend.dispatchTouch(params)
        }
        return true
    }

    fun clear() {
        activity.runOnUiThread {
            if (cleared) return@runOnUiThread
            cleared = true
            try { backend.destroy() } finally {
                components.values.forEach { it.release() }
                components.clear()
                imageScope.cancel()
            }
        }
    }

    private fun mapResult(params: JSONObject, result: Result<JSONObject>) {
        val requestId = params.optString("requestId")
        if (requestId.isEmpty()) return
        sendEvent("mapResult", JSONObject().put("id", params.optString("id"))
            .put("requestId", requestId).put("ok", result.isSuccess)
            .put("data", result.getOrElse { JSONObject().put("errMsg", it.message ?: "Native map operation failed") }))
    }

    private fun handleMap(apiName: String, id: String, params: JSONObject) {
        try {
            when (apiName) {
                "mapMount" -> {
                    unmountComponent(id)
                    val component = NativeMapComponent(id, params)
                    components[id] = component
                    backend.attach(id, component.type, component.view, component::onVisibilityChanged)
                    component.update(params)
                }
                "mapUpdate" -> {
                    val component = components[id] as? NativeMapComponent ?: error("Map not found")
                    component.update(params)
                    mapResult(params, Result.success(JSONObject()))
                }
                "mapUnmount" -> unmountComponent(id)
                "mapContext" -> {
                    val component = components[id] as? NativeMapComponent ?: error("Map not found")
                    component.invoke(params)
                }
            }
        } catch (error: Exception) {
            if (apiName == "mapMount") unmountComponent(id)
            mapResult(params, Result.failure(error))
        }
    }

    private inner class NativeMapComponent(id: String, private val mount: JSONObject) :
        BaseNativeComponent(id, "native/map"), MapEvents, DefaultLifecycleObserver {
        private var released = false
        private var mounted = false
        private val instance: MapInstance = MapProviders.create(activity, this)
        override val view: View get() = instance.view

        private var resumed = false
        private var presented = false
        init { activity.lifecycle.addObserver(this) }
        override fun onVisibilityChanged(visible: Boolean) {
            presented = visible
            syncLifecycle()
        }
        private fun syncLifecycle() {
            if (released) return
            val shouldResume = presented && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (resumed == shouldResume) return
            resumed = shouldResume
            if (resumed) instance.resume() else instance.pause()
        }
        override fun update(params: JSONObject) {
            NativeComponentLayout.from(params)?.let { backend.updateLayout(id, it) }
            if (!params.optBoolean("layoutOnly")) instance.update(params.optJSONObject("props") ?: JSONObject())
        }
        override fun ready() {
            // A provider may report synchronously from create; wait until the host owns its view.
            webView.post {
                if (released || mounted) return@post
                mounted = true
                mapResult(mount, Result.success(JSONObject().put("nativeComponentBackend", JSONObject()
                    .put("name", backend.capabilities.name)
                    .put("supportsPageBackground", backend.capabilities.supportsPageBackground))))
                event("rendersuccess")
            }
        }
        override fun event(name: String, detail: JSONObject) {
            if (!released) sendEvent("mapEvent", JSONObject().put("id", id).put("event", name).put("detail", detail))
        }
        override fun error(message: String) {
            if (!mounted) mapResult(mount, Result.failure(IllegalStateException(message)))
            event("error", JSONObject().put("errMsg", message))
        }
        fun invoke(params: JSONObject) {
            var settled = false
            instance.invoke(params.optString("command"), params.optJSONObject("args") ?: JSONObject()) { result ->
                if (!released && !settled) { settled = true; mapResult(params, result) }
            }
        }
        override fun onResume(owner: LifecycleOwner) { syncLifecycle() }
        override fun onPause(owner: LifecycleOwner) { resumed = false; instance.pause() }
        override fun release() {
            if (released) return
            released = true
            activity.lifecycle.removeObserver(this)
            instance.destroy()
        }
    }

    private fun mountComponent(type: String, id: String, params: JSONObject) {
        val existing = components[id]
        if (existing != null && existing.type != type) {
            unmountComponent(id)
        }
        val component = components.getOrPut(id) {
            createComponent(type, id).also { component ->
                try { backend.attach(id, type, component.view, component::onVisibilityChanged) }
                catch (error: Exception) {
                    try { backend.detach(id) } finally { component.release() }
                    throw error
                }
            }
        }
        component.update(params)
    }

    private fun updateComponent(type: String, id: String, params: JSONObject) {
        components[id]?.let { component ->
            component.update(params)
        } ?: mountComponent(type, id, params)
    }

    private fun unmountComponent(id: String) {
        components.remove(id)?.let { component ->
            try { backend.detach(id) } finally { component.release() }
        }
    }

    private fun createComponent(type: String, id: String): NativeComponent {
        return when (type) {
            COVER_VIEW_TYPE -> NativeCoverViewComponent(id)
            COVER_IMAGE_TYPE -> NativeCoverImageComponent(id)
            WEB_VIEW_TYPE -> NativeWebViewComponent(id)
            VIDEO_TYPE -> NativeVideoComponent(id)
            else -> error("Unsupported native component type: $type")
        }
    }

    private fun sendEvent(eventName: String, body: JSONObject) {
        val message = JSONObject().apply {
            put("type", eventName)
            put("body", body)
        }
        webView.post { webView.postMessage(message.toString()) }
    }

    private interface NativeComponent {
        val type: String
        val view: View
        fun update(params: JSONObject)
        fun onVisibilityChanged(visible: Boolean) = Unit
        fun release()
    }

    private abstract inner class BaseNativeComponent(
        protected val id: String,
        override val type: String,
    ) : NativeComponent {

        override fun update(params: JSONObject) {
            applyLayout(params)
            view.isClickable = params.optBoolean("tappable", view.isClickable)
            view.setOnClickListener(
                if (params.optBoolean("tappable", false)) {
                    View.OnClickListener {
                        sendEvent("bindtap", JSONObject().apply { put("id", id) })
                    }
                } else {
                    null
                }
            )
        }

        protected fun applyLayout(params: JSONObject) {
            NativeComponentLayout.from(params)?.let { backend.updateLayout(id, it) }
        }

        protected fun applyCommonStyle(params: JSONObject) {
            val style = params.optJSONObject("style") ?: return
            val borderRadius = parseCssPx(style.optString("borderRadius")).toFloat()
            val background = GradientDrawable().apply {
                setColor(parseCssColor(style.optString("backgroundColor")) ?: Color.TRANSPARENT)
                cornerRadius = borderRadius
            }
            view.background = background
            view.clipToOutline = borderRadius > 0f
        }

        override fun release() = Unit
    }

    private inner class NativeCoverViewComponent(id: String) :
        BaseNativeComponent(id, COVER_VIEW_TYPE) {
        override val view: TextView = TextView(activity).apply {
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
        }

        override fun update(params: JSONObject) {
            super.update(params)
            applyCommonStyle(params)
            val style = params.optJSONObject("style")
            view.text = params.optString("text")
            style?.let {
                parseCssColor(it.optString("color"))?.let(view::setTextColor)
                parseCssPx(it.optString("fontSize")).takeIf { size -> size > 0 }?.let { size ->
                    view.textSize = pxToSp(size.toFloat())
                }
                view.typeface = if (isBoldFontWeight(it.optString("fontWeight"))) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }
                view.gravity = when (it.optString("textAlign")) {
                    "center" -> Gravity.CENTER
                    "right", "end" -> Gravity.END or Gravity.CENTER_VERTICAL
                    else -> Gravity.START or Gravity.CENTER_VERTICAL
                }
                val lineHeight = parseCssPx(it.optString("lineHeight"))
                if (lineHeight > 0) {
                    view.setLineSpacing(lineHeight.toFloat() - view.textSize, 1f)
                }
            }
        }
    }

    private inner class NativeCoverImageComponent(id: String) :
        BaseNativeComponent(id, COVER_IMAGE_TYPE) {
        override val view: ImageView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        private var src = ""
        private var imageJob: Job? = null

        override fun update(params: JSONObject) {
            super.update(params)
            applyCommonStyle(params)
            view.scaleType = when (params.optString("mode")) {
                "scaleToFill" -> ImageView.ScaleType.FIT_XY
                "aspectFill" -> ImageView.ScaleType.CENTER_CROP
                "center" -> ImageView.ScaleType.CENTER
                "top" -> ImageView.ScaleType.FIT_START
                "bottom" -> ImageView.ScaleType.FIT_END
                else -> ImageView.ScaleType.FIT_CENTER
            }
            val nextSrc = params.optString("src", src)
            if (nextSrc != src) {
                src = nextSrc
                loadSource()
            }
        }

        override fun release() {
            imageJob?.cancel()
            view.setImageDrawable(null)
        }

        private fun loadSource() {
            imageJob?.cancel()
            view.setImageDrawable(null)
            if (src.isEmpty()) {
                return
            }
            imageJob = imageScope.launch {
                try {
                    val drawable = withContext(Dispatchers.IO) {
                        activity.imageLoader.execute(
                            ImageRequest.Builder(activity)
                                .data(src)
                                .allowHardware(false)
                                .build()
                        ).drawable
                    }
                    view.setImageDrawable(drawable)
                    sendEvent("bindload", JSONObject().apply { put("id", id) })
                } catch (e: Exception) {
                    LogUtils.e(TAG, "Failed to load cover-image: ${e.message}")
                    sendEvent("binderror", JSONObject().apply {
                        put("id", id)
                        put("errMsg", e.message ?: "cover-image load failed")
                    })
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class NativeWebViewComponent(id: String) :
        BaseNativeComponent(id, WEB_VIEW_TYPE) {
        override val view: WebView = WebView(activity).apply {
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = "$userAgentString XFunMiniProgram/${com.didi.dimina.BuildConfig.SDK_VERSION} miniProgram"
            }
            addJavascriptInterface(
                EmbeddedWebViewBridge { message -> embeddedMessageHandler(message) },
                EMBEDDED_WEB_VIEW_BRIDGE,
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !NativeWebViewPolicy.isSupportedSource(request.url.toString())

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !NativeWebViewPolicy.isSupportedSource(url)

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    loadFailed = false
                    reportedErrorKey = null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (released || loadFailed || url == ABOUT_BLANK_URL) return
                    injectComponentScript()
                    sendEvent("bindload", eventBody(url))
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        reportError(
                            request.url.toString(),
                            "${error.errorCode}: ${error.description}",
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame) {
                        reportError(
                            request.url.toString(),
                            "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase}",
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    reportError(error.url, "SSL ${error.primaryError}")
                }
            }
        }

        private var src = ""
        private var bridgeId = ""
        private var attributes: JSONObject? = null
        private var loadFailed = false
        private var reportedErrorKey: String? = null
        private var released = false

        override fun update(params: JSONObject) {
            super.update(params)
            bridgeId = params.optString("bridgeId", bridgeId)
            attributes = params.optJSONObject("attributes")

            val nextSrc = params.optString("url").ifEmpty {
                params.optString("src", src)
            }
            if (nextSrc == src) return

            src = nextSrc
            loadSource()
        }

        override fun release() {
            released = true
            view.stopLoading()
            view.removeJavascriptInterface(EMBEDDED_WEB_VIEW_BRIDGE)
            view.webViewClient = WebViewClient()
            view.loadUrl(ABOUT_BLANK_URL)
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
        }

        private fun loadSource() {
            loadFailed = false
            reportedErrorKey = null
            if (src.isEmpty()) {
                view.stopLoading()
                view.loadUrl(ABOUT_BLANK_URL)
                return
            }
            if (!NativeWebViewPolicy.isSupportedSource(src)) {
                reportError(src, "unsupported web-view URL")
                return
            }
            view.loadUrl(src)
        }

        private fun injectComponentScript() {
            view.evaluateJavascript(
                NativeWebViewPolicy.bootstrapScript(bridgeId, attributes),
                null,
            )
        }

        private fun reportError(url: String, message: String) {
            val errorKey = "$url|$message"
            if (reportedErrorKey == errorKey) return
            loadFailed = true
            reportedErrorKey = errorKey
            LogUtils.e(TAG, "Failed to load web-view $url: $message")
            sendEvent("binderror", eventBody(url).apply {
                put("fullUrl", url)
                put("errMsg", message)
            })
        }

        private fun eventBody(url: String): JSONObject = JSONObject().apply {
            put("id", id)
            put("src", src)
            put("url", url)
        }
    }

    private class EmbeddedWebViewBridge(
        private val messageHandler: (JSONObject) -> Unit,
    ) {
        @Suppress("unused")
        @JavascriptInterface
        fun invoke(message: String) {
            try {
                messageHandler(JSONObject(message))
            } catch (e: Exception) {
                LogUtils.e(TAG, "Embedded web-view bridge failed: ${e.message}")
            }
        }
    }

    private inner class NativeVideoComponent(
        private val id: String,
    ) : NativeComponent {
        override val type: String = VIDEO_TYPE
        override val view: FrameLayout = FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            setOnClickListener { toggleControlBar() }
        }
        private val videoView: AspectRatioVideoView = AspectRatioVideoView(activity).also {
            it.isClickable = true
            it.setOnClickListener { toggleControlBar() }
            view.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }
        private val playButton: ImageButton = ImageButton(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setImageResource(android.R.drawable.ic_media_play)
            setOnClickListener {
                togglePlay()
            }
        }
        private val centerPlayButton: ImageButton = ImageButton(activity).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setColorFilter(Color.WHITE)
            setImageResource(android.R.drawable.ic_media_play)
            setOnClickListener { play() }
        }.also {
            view.addView(
                it,
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            )
        }
        private val seekBar: SeekBar = SeekBar(activity).apply {
            max = 1
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(120, 255, 255, 255))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        timeText.text = formatVideoTime(progress, videoView.duration)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = false
                    videoView.seekTo(seekBar?.progress ?: 0)
                    updateControlProgress()
                }
            })
        }
        private val timeText: TextView = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            text = "00:00/00:00"
        }
        private val controlBar: LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            addView(
                playButton,
                LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT)
            )
            addView(
                seekBar,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                timeText,
                LinearLayout.LayoutParams(dp(86), LinearLayout.LayoutParams.MATCH_PARENT)
            )
        }.also {
            view.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(44),
                    Gravity.BOTTOM
                )
            )
        }

        private val handler = Handler(Looper.getMainLooper())
        private var mediaPlayer: MediaPlayer? = null
        private var src: String = ""
        private var controls: Boolean = true
        private var controlsVisible: Boolean = true
        private var showProgress: Boolean = true
        private var showPlayBtn: Boolean = true
        private var showCenterPlayBtn: Boolean = true
        private var autoplay: Boolean = false
        private var loop: Boolean = false
        private var muted: Boolean = false
        private var initialTimeMs: Int = 0
        private var isPrepared: Boolean = false
        private var pendingPlay: Boolean = false
        private var timeUpdateRunning: Boolean = false
        private var isUserSeeking: Boolean = false

        private val timeUpdateTask = object : Runnable {
            override fun run() {
                if (videoView.isPlaying) {
                    updateControlProgress()
                    sendEvent("bindtimeupdate", baseEventBody().apply {
                        put("currentTime", videoView.currentPosition / 1000.0)
                        put("duration", videoView.duration.coerceAtLeast(0) / 1000.0)
                    })
                }
                if (timeUpdateRunning) {
                    handler.postDelayed(this, TIME_UPDATE_INTERVAL_MS)
                }
            }
        }

        init {
            videoView.setOnPreparedListener { player ->
                mediaPlayer = player
                isPrepared = true
                videoView.setVideoSize(player.videoWidth, player.videoHeight)
                player.isLooping = loop
                applyMuted()
                val firstFrameTimeMs = if (initialTimeMs > 0) initialTimeMs else FIRST_FRAME_SEEK_MS
                if (firstFrameTimeMs > 0) {
                    player.seekTo(firstFrameTimeMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                }
                sendEvent("bindloadedmetadata", baseEventBody().apply {
                    put("duration", videoView.duration.coerceAtLeast(0) / 1000.0)
                })
                updateControlProgress()
                if (autoplay || pendingPlay) {
                    pendingPlay = false
                    play()
                } else {
                    updatePlayButton()
                }
            }
            videoView.setOnCompletionListener {
                updatePlayButton()
                updateControlProgress()
                sendEvent("bindended", baseEventBody())
                if (!loop) {
                    stopTimeUpdates()
                }
            }
            videoView.setOnErrorListener { _, what, extra ->
                sendEvent("binderror", baseEventBody().apply {
                    put("errMsg", "video:error what=$what extra=$extra")
                })
                true
            }
        }

        override fun update(params: JSONObject) {
            applyLayout(params)
            controls = params.optBoolean("controls", controls)
            showProgress = params.optBoolean("showProgress", showProgress)
            showPlayBtn = params.optBoolean("showPlayBtn", showPlayBtn)
            showCenterPlayBtn = params.optBoolean("showCenterPlayBtn", showCenterPlayBtn)
            autoplay = params.optBoolean("autoplay", autoplay)
            loop = params.optBoolean("loop", loop)
            muted = params.optBoolean("muted", muted)
            initialTimeMs = (params.optDouble("initialTime", initialTimeMs / 1000.0) * 1000).roundToInt()
            applyControlsVisibility()

            mediaPlayer?.isLooping = loop
            applyMuted()

            val nextSrc = params.optString("src", src)
            if (nextSrc != src) {
                src = nextSrc
                loadSource()
            } else if (autoplay && !videoView.isPlaying) {
                play()
            }
        }

        private fun applyLayout(params: JSONObject) {
            NativeComponentLayout.from(params)?.let { backend.updateLayout(id, it) }
        }

        fun handleCommand(params: JSONObject) {
            when (params.optString("command")) {
                "play" -> play()
                "pause" -> pause()
                "stop" -> stop()
                "seek" -> seek(params.optDouble("position", 0.0))
                "playbackRate" -> playbackRate(params.optDouble("rate", 1.0).toFloat())
                "requestFullScreen",
                "exitFullScreen",
                "requestBackgroundPlayback",
                "exitBackgroundPlayback",
                "exitPictureInPicture",
                "showStatusBar",
                "hideStatusBar",
                -> LogUtils.d(TAG, "Video command is not implemented: ${params.optString("command")}")
            }
        }

        override fun release() {
            stopTimeUpdates()
            videoView.stopPlayback()
            mediaPlayer = null
            isPrepared = false
            pendingPlay = false
        }

        private fun loadSource() {
            if (src.isEmpty()) {
                return
            }
            try {
                isPrepared = false
                mediaPlayer = null
                pendingPlay = autoplay
                videoView.setVideoURI(resolveVideoSource(src))
                videoView.requestFocus()
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to load video source: ${e.message}")
                sendEvent("binderror", baseEventBody().apply {
                    put("errMsg", "video:error ${e.message}")
                })
            }
        }

        private fun resolveVideoSource(source: String): Uri {
            val uri = Uri.parse(source)
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> uri
                null, "file", PathUtils.VIRTUAL_SCHEME -> Uri.fromFile(
                    File(PathUtils.pathToAppResource(activity, source, activity.getMiniProgram().appId)),
                )
                else -> throw IllegalArgumentException("unsupported video source scheme")
            }
        }

        private fun togglePlay() {
            if (videoView.isPlaying) {
                pause()
            } else {
                play()
            }
        }

        private fun play() {
            if (!isPrepared || mediaPlayer == null) {
                pendingPlay = true
                updatePlayButton()
                return
            }
            videoView.start()
            pendingPlay = false
            updatePlayButton()
            updateControlProgress()
            startTimeUpdates()
            sendEvent("bindplay", baseEventBody())
        }

        private fun pause() {
            pendingPlay = false
            if (videoView.isPlaying) {
                videoView.pause()
            }
            updatePlayButton()
            updateControlProgress()
            stopTimeUpdates()
            sendEvent("bindpause", baseEventBody())
        }

        private fun stop() {
            pendingPlay = false
            if (videoView.canSeekBackward() || videoView.canSeekForward() || videoView.isPlaying) {
                videoView.pause()
                videoView.seekTo(0)
            }
            updatePlayButton()
            updateControlProgress()
            stopTimeUpdates()
            sendEvent("bindpause", baseEventBody())
        }

        private fun seek(position: Double) {
            videoView.seekTo((position * 1000).roundToInt().coerceAtLeast(0))
            updateControlProgress()
        }

        private fun playbackRate(rate: Float) {
            try {
                val player = mediaPlayer ?: return
                player.playbackParams = player.playbackParams.setSpeed(rate)
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to set video playbackRate: ${e.message}")
            }
        }

        private fun applyMuted() {
            val volume = if (muted) 0f else 1f
            mediaPlayer?.setVolume(volume, volume)
        }

        private fun applyControlsVisibility() {
            if (!controls) {
                controlsVisible = false
            } else if (controlBar.visibility == View.GONE) {
                controlsVisible = true
            }
            controlBar.visibility = if (controls && controlsVisible) View.VISIBLE else View.GONE
            playButton.visibility = if (showPlayBtn) View.VISIBLE else View.GONE
            seekBar.visibility = if (showProgress) View.VISIBLE else View.GONE
            timeText.visibility = if (showProgress) View.VISIBLE else View.GONE
            updatePlayButton()
            updateControlProgress()
        }

        private fun toggleControlBar() {
            if (!controls) {
                return
            }
            controlsVisible = !controlsVisible
            applyControlsVisibility()
        }

        private fun updatePlayButton() {
            playButton.setImageResource(
                if (videoView.isPlaying) {
                    android.R.drawable.ic_media_pause
                } else {
                    android.R.drawable.ic_media_play
                }
            )
            centerPlayButton.visibility = if (
                controls &&
                controlsVisible &&
                showCenterPlayBtn &&
                !videoView.isPlaying &&
                !pendingPlay
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        private fun updateControlProgress() {
            if (!controls || isUserSeeking) {
                return
            }
            val duration = videoView.duration.coerceAtLeast(0)
            val position = videoView.currentPosition.coerceAtLeast(0)
            seekBar.max = duration.coerceAtLeast(1)
            seekBar.progress = position.coerceIn(0, seekBar.max)
            timeText.text = formatVideoTime(position, duration)
        }

        private fun startTimeUpdates() {
            if (timeUpdateRunning) {
                return
            }
            timeUpdateRunning = true
            handler.post(timeUpdateTask)
        }

        private fun stopTimeUpdates() {
            timeUpdateRunning = false
            handler.removeCallbacks(timeUpdateTask)
        }

        private fun baseEventBody(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("src", src)
            }
        }
    }

    companion object {
        private const val TAG = "NativeComponentHost"
        private const val VIDEO_TYPE = "native/video"
        private const val COVER_VIEW_TYPE = "native/cover-view"
        private const val COVER_IMAGE_TYPE = "native/cover-image"
        private const val WEB_VIEW_TYPE = NativeWebViewPolicy.TYPE
        private const val EMBEDDED_WEB_VIEW_BRIDGE = "DiminaEmbeddedWebViewBridge"
        private const val ABOUT_BLANK_URL = "about:blank"
        private const val TIME_UPDATE_INTERVAL_MS = 250L
        private const val FIRST_FRAME_SEEK_MS = 1
        private val SUPPORTED_TYPES = NativeWebViewPolicy.supportedComponentTypes + "native/map"
    }

    private fun pxToSp(px: Float): Float {
        return px / activity.resources.displayMetrics.scaledDensity
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).roundToInt()
    }
}

private fun parseCssPx(value: String?): Int {
    val token = value
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.removeSuffix("px")
        ?: return 0
    return token.toFloatOrNull()?.roundToInt() ?: 0
}

private fun isBoldFontWeight(value: String?): Boolean {
    val normalized = value?.trim() ?: return false
    return normalized == "bold" || (normalized.toIntOrNull() ?: 0) >= 600
}

private fun parseCssColor(value: String?): Int? {
    val color = value?.trim()?.lowercase().orEmpty()
    if (color.isEmpty()) {
        return null
    }
    if (color == "transparent") {
        return Color.TRANSPARENT
    }
    if (color.startsWith("rgb(") || color.startsWith("rgba(")) {
        val values = color
            .substringAfter("(")
            .substringBefore(")")
            .split(",")
            .map { it.trim() }
        val red = values.getOrNull(0)?.toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
        val green = values.getOrNull(1)?.toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
        val blue = values.getOrNull(2)?.toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
        val alpha = values.getOrNull(3)?.toFloatOrNull()?.let {
            if (it <= 1f) (it * 255).roundToInt() else it.roundToInt()
        }?.coerceIn(0, 255) ?: 255
        return Color.argb(alpha, red, green, blue)
    }
    return runCatching { Color.parseColor(color) }.getOrNull()
}

private fun formatVideoTime(positionMs: Int, durationMs: Int): String {
    return "${formatVideoTimePart(positionMs)}/${formatVideoTimePart(durationMs.coerceAtLeast(0))}"
}

private fun formatVideoTimePart(timeMs: Int): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

private class AspectRatioVideoView(context: android.content.Context) : VideoView(context) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        if (videoWidth <= 0 || videoHeight <= 0 || parentWidth <= 0 || parentHeight <= 0) {
            setMeasuredDimension(parentWidth, parentHeight)
            return
        }

        val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val measuredWidth: Int
        val measuredHeight: Int
        if (videoRatio > parentRatio) {
            measuredWidth = parentWidth
            measuredHeight = (parentWidth / videoRatio).roundToInt()
        } else {
            measuredHeight = parentHeight
            measuredWidth = (parentHeight * videoRatio).roundToInt()
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }
}
