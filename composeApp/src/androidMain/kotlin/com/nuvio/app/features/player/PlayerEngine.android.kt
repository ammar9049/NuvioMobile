package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "NuvioPlayer"
private val mainHandler = Handler(Looper.getMainLooper())
private val trackListJson = Json { ignoreUnknownKeys = true }

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestSourceUrl = rememberUpdatedState(sourceUrl)
    val coroutineScope = rememberCoroutineScope()

    val sanitizedHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }

    var isLoading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var isEnded by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var trackList by remember { mutableStateOf("[]") }
    var externalSubId by remember { mutableIntStateOf(-1) }
    // Guards against emitting an error for a normal EOF
    var fileWasLoaded by remember { mutableStateOf(false) }

    fun buildSnapshot() = PlayerPlaybackSnapshot(
        isLoading = isLoading,
        isPlaying = isPlaying,
        isEnded = isEnded,
        durationMs = durationMs,
        positionMs = positionMs,
        bufferedPositionMs = bufferedMs,
        playbackSpeed = playbackSpeed,
    )

    fun pushSnapshot() = latestOnSnapshot.value(buildSnapshot())

    val mpvView = remember { MPVView(context, null) }

    DisposableEffect(Unit) {
        MPVLib.create(context.applicationContext)
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("hwdec", "auto")
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("network-timeout", "15")
        MPVLib.setOptionString(
            "user-agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        MPVLib.setOptionString("demuxer-max-bytes", "104857600")
        MPVLib.setOptionString("demuxer-readahead-secs", "20")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("sub-ass", "yes")
        MPVLib.setOptionString("sub-auto", "fuzzy")
        MPVLib.setOptionString("gpu-shader-cache-dir", context.cacheDir.path)
        MPVLib.setOptionString("idle", "once")
        MPVLib.init()
        mpvView.onMpvInit()
        onDispose {
            mpvView.destroy()
            MPVLib.destroy()
        }
    }

    LaunchedEffect(sourceUrl, sourceAudioUrl, sanitizedHeaders) {
        isLoading = true
        isEnded = false
        fileWasLoaded = false

        val headerStr = sanitizedHeaders.entries
            .filter { !it.key.equals("Range", ignoreCase = true) }
            .joinToString("\n") { "${it.key}: ${it.value}" }
        MPVLib.setOptionString("http-header-fields", headerStr)

        if (!sourceAudioUrl.isNullOrBlank()) {
            // Load video and attach the external audio track in one shot.
            MPVLib.command(arrayOf("loadfile", sourceUrl, "replace", "audio-file=$sourceAudioUrl"))
        } else {
            MPVLib.command(arrayOf("loadfile", sourceUrl))
        }
    }

    DisposableEffect(Unit) {
        val observer = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {
                if (property == "track-list") {
                    val json = MPVLib.getPropertyString("track-list") ?: "[]"
                    mainHandler.post { trackList = json }
                }
            }

            override fun eventProperty(property: String, value: Long) {
                mainHandler.post {
                    when (property) {
                        "time-pos" -> positionMs = (value * 1000L).coerceAtLeast(0L)
                        "duration" -> durationMs = (value * 1000L).coerceAtLeast(0L)
                        "demuxer-cache-time" -> bufferedMs = (value * 1000L).coerceAtLeast(0L)
                    }
                    pushSnapshot()
                }
            }

            override fun eventProperty(property: String, value: Boolean) {
                mainHandler.post {
                    when (property) {
                        "pause" -> {
                            isPlaying = !value
                            mpvView.keepScreenOn = isPlaying && !isEnded
                        }
                        "paused-for-cache" -> isLoading = value
                        "eof-reached" -> if (value) {
                            isEnded = true
                            isLoading = false
                            mpvView.keepScreenOn = false
                        }
                    }
                    pushSnapshot()
                }
            }

            override fun eventProperty(property: String, value: String) {
                if (property == "speed") {
                    mainHandler.post {
                        playbackSpeed = value.toFloatOrNull() ?: 1f
                        pushSnapshot()
                    }
                }
            }

            override fun eventProperty(property: String, value: Double) { /* unused */ }

            override fun event(eventId: Int) {
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> mainHandler.post {
                        fileWasLoaded = true
                        isLoading = false
                        isEnded = false
                        latestOnError.value(null)
                        trackList = MPVLib.getPropertyString("track-list") ?: "[]"
                        Log.d(TAG, "FILE_LOADED: tracks=$trackList")
                        pushSnapshot()
                    }
                    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> mainHandler.post {
                        if (!fileWasLoaded) {
                            Log.w(TAG, "END_FILE without prior FILE_LOADED – playback error")
                            latestOnError.value("Playback failed")
                        } else {
                            isEnded = true
                        }
                        fileWasLoaded = false
                        isLoading = false
                        mpvView.keepScreenOn = false
                        pushSnapshot()
                    }
                    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> mainHandler.post {
                        isLoading = false
                        pushSnapshot()
                    }
                }
            }
        }

        MPVLib.addObserver(observer)
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_STRING)

        onDispose { MPVLib.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        PlayerPictureInPictureManager.registerPausePlaybackCallback {
            MPVLib.setPropertyBoolean("pause", true)
        }
        onDispose { PlayerPictureInPictureManager.registerPausePlaybackCallback(null) }
    }

    DisposableEffect(lifecycleOwner) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (playWhenReady) MPVLib.setPropertyBoolean("pause", false)
                Lifecycle.Event.ON_STOP -> {
                    val isInPiP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        activity?.isInPictureInPictureMode == true
                    if (!isInPiP || activity?.isFinishing == true) {
                        MPVLib.setPropertyBoolean("pause", true)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(playWhenReady) {
        MPVLib.setPropertyBoolean("pause", !playWhenReady)
        pushSnapshot()
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(250L)
            var changed = false
            MPVLib.getPropertyDouble("time-pos")?.let {
                val v = (it * 1000.0).toLong().coerceAtLeast(0L)
                if (positionMs != v) { positionMs = v; changed = true }
            }
            MPVLib.getPropertyDouble("duration")?.let {
                val v = (it * 1000.0).toLong().coerceAtLeast(0L)
                if (durationMs != v) { durationMs = v; changed = true }
            }
            MPVLib.getPropertyDouble("demuxer-cache-time")?.let {
                val v = (it * 1000.0).toLong().coerceAtLeast(0L)
                if (bufferedMs != v) { bufferedMs = v; changed = true }
            }
            if (changed) pushSnapshot()
        }
    }

    LaunchedEffect(Unit) {
        onControllerReady(object : PlayerEngineController {
            override fun play() {
                MPVLib.setPropertyBoolean("pause", false)
            }

            override fun pause() {
                MPVLib.setPropertyBoolean("pause", true)
            }

            override fun seekTo(positionMs: Long) {
                MPVLib.command(
                    arrayOf("seek", (positionMs / 1000.0).toString(), "absolute", "exact")
                )
            }

            override fun seekBy(offsetMs: Long) {
                MPVLib.command(
                    arrayOf("seek", (offsetMs / 1000.0).toString(), "relative", "exact")
                )
            }

            override fun retry() {
                isLoading = true
                isEnded = false
                fileWasLoaded = false
                MPVLib.command(arrayOf("loadfile", latestSourceUrl.value))
            }

            override fun setPlaybackSpeed(speed: Float) {
                MPVLib.setPropertyDouble("speed", speed.toDouble())
            }

            override fun getAudioTracks(): List<AudioTrack> =
                parseMpvTracks(trackList, "audio").mapIndexed { idx, t ->
                    AudioTrack(
                        index = idx,
                        id = t.id.toString(),
                        label = t.label,
                        language = t.lang,
                        isSelected = t.selected,
                    )
                }

            override fun getSubtitleTracks(): List<SubtitleTrack> =
                parseMpvTracks(trackList, "sub").mapIndexed { idx, t ->
                    SubtitleTrack(
                        index = idx,
                        id = t.id.toString(),
                        label = t.label,
                        language = t.lang,
                        isSelected = t.selected,
                        isForced = inferForcedSubtitleTrack(
                            label = t.label,
                            language = t.lang,
                            trackId = t.id.toString(),
                            hasForcedSelectionFlag = false,
                        ),
                    )
                }

            override fun selectAudioTrack(index: Int) {
                val track = parseMpvTracks(trackList, "audio").getOrNull(index) ?: return
                Log.d(TAG, "selectAudioTrack: index=$index id=${track.id}")
                MPVLib.setPropertyInt("aid", track.id)
            }

            override fun selectSubtitleTrack(index: Int) {
                Log.d(TAG, "selectSubtitleTrack: index=$index")
                if (index < 0) {
                    MPVLib.setPropertyString("sid", "no")
                    return
                }
                val track = parseMpvTracks(trackList, "sub").getOrNull(index) ?: return
                MPVLib.setPropertyInt("sid", track.id)
            }

            override fun setSubtitleUri(url: String) {
                Log.d(TAG, "setSubtitleUri: url=$url")
                if (externalSubId >= 0) {
                    MPVLib.command(arrayOf("sub-remove", externalSubId.toString()))
                    externalSubId = -1
                }
                MPVLib.command(arrayOf("sub-add", url, "select"))
                // Capture the newly added external track id after MPV processes the command.
                coroutineScope.launch {
                    delay(300L)
                    val json = MPVLib.getPropertyString("track-list") ?: "[]"
                    trackList = json
                    externalSubId = parseMpvTracks(json, "sub")
                        .lastOrNull { it.external }?.id ?: -1
                    Log.d(TAG, "setSubtitleUri: externalSubId=$externalSubId")
                }
            }

            override fun clearExternalSubtitle() {
                Log.d(TAG, "clearExternalSubtitle: externalSubId=$externalSubId")
                if (externalSubId >= 0) {
                    MPVLib.command(arrayOf("sub-remove", externalSubId.toString()))
                    externalSubId = -1
                }
                MPVLib.setPropertyString("sid", "no")
                trackList = MPVLib.getPropertyString("track-list") ?: trackList
            }

            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                Log.d(TAG, "clearExternalSubtitleAndSelect: trackIndex=$trackIndex externalSubId=$externalSubId")
                if (externalSubId >= 0) {
                    MPVLib.command(arrayOf("sub-remove", externalSubId.toString()))
                    externalSubId = -1
                }
                val updatedJson = MPVLib.getPropertyString("track-list") ?: trackList
                trackList = updatedJson
                if (trackIndex >= 0) {
                    val track = parseMpvTracks(updatedJson, "sub").getOrNull(trackIndex)
                    if (track != null) MPVLib.setPropertyInt("sid", track.id)
                } else {
                    MPVLib.setPropertyString("sid", "no")
                }
            }

            override fun applySubtitleStyle(style: SubtitleStyleState) {
                MPVLib.setPropertyInt("sub-font-size", style.fontSizeSp)
                MPVLib.setPropertyString("sub-color", style.textColor.toMpvColor())
                MPVLib.setPropertyInt("sub-border-size", if (style.outlineEnabled) 2 else 0)
                MPVLib.setPropertyInt("sub-margin-y", style.bottomOffset)
            }
        })
    }

    LaunchedEffect(resizeMode) {
        when (resizeMode) {
            PlayerResizeMode.Fit -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
            }
            PlayerResizeMode.Fill -> MPVLib.setPropertyString("keepaspect", "no")
            PlayerResizeMode.Zoom -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 1.0)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mpvView.apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
        },
        update = { /* MPV drives its own surface; resize mode is handled via LaunchedEffect */ },
    )
}

private data class MpvTrackInfo(
    val id: Int,
    val label: String,
    val lang: String?,
    val selected: Boolean,
    val external: Boolean,
)

private fun parseMpvTracks(json: String, type: String): List<MpvTrackInfo> = try {
    trackListJson.parseToJsonElement(json).jsonArray
        .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == type }
        .mapIndexed { listIdx, elem ->
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapIndexed null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            val lang = obj["lang"]?.jsonPrimitive?.contentOrNull
            val selected = obj["selected"]?.jsonPrimitive?.booleanOrNull ?: false
            val external = obj["external"]?.jsonPrimitive?.booleanOrNull ?: false
            val label = title?.takeIf { it.isNotBlank() }
                ?: lang?.takeIf { it.isNotBlank() }
                ?: "Track ${listIdx + 1}"
            MpvTrackInfo(id, label, lang, selected, external)
        }
        .filterNotNull()
} catch (e: Exception) {
    Log.w(TAG, "parseMpvTracks failed for type=$type", e)
    emptyList()
}

private fun Color.toMpvColor(): String {
    fun fmt(v: Float) = "%.3f".format(v)
    return "${fmt(red)}/${fmt(green)}/${fmt(blue)}/${fmt(alpha)}"
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
