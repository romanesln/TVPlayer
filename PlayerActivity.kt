package com.romane.tvplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private data class Entry(val uri: Uri, val title: String?)

    private lateinit var playerView: PlayerView
    private lateinit var delayPanel: View
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var txtDelay: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtInfo: TextView

    private var player: ExoPlayer? = null
    private val delayProcessor = AudioDelayProcessor()
    private val handler = Handler(Looper.getMainLooper())
    private val hideTitle = Runnable { txtTitle.visibility = View.GONE }
    private val hideInfo = Runnable { txtInfo.visibility = View.GONE }
    private var endedByCompletion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        delayPanel = findViewById(R.id.delay_panel)
        btnMinus = findViewById(R.id.btn_delay_minus)
        btnPlus = findViewById(R.id.btn_delay_plus)
        txtDelay = findViewById(R.id.txt_delay)
        txtTitle = findViewById(R.id.txt_title)
        txtInfo = findViewById(R.id.txt_info)

        delayProcessor.targetDelayMs = prefs().getInt(KEY_DELAY, 0)
        updateDelayLabel()

        btnMinus.setOnClickListener { changeDelay(-STEP_MS) }
        btnPlus.setOnClickListener { changeDelay(STEP_MS) }

        playerView.setShowSubtitleButton(true)
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        playerView.controllerShowTimeoutMs = 5000
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                delayPanel.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // ------------------------------------------------------------------ Intent

    private fun handleIntent(intent: Intent) {
        val entries = parsePlaylist(intent)
        Log.i(TAG, "Intent reçu : action=${intent.action} data=${intent.data} " +
            "type=${intent.type} extras=${intent.extras?.keySet()} éléments=${entries.size}")

        if (entries.isEmpty()) {
            releasePlayer()
            playerView.player = null
            txtTitle.text = getString(R.string.waiting)
            txtTitle.visibility = View.VISIBLE
            return
        }

        val data = intent.data
        val startIndex = if (data != null) {
            entries.indexOfFirst { it.uri == data }.coerceAtLeast(0)
        } else {
            0
        }
        val startPosition = if (intent.getBooleanExtra("from_start", false)) {
            0L
        } else {
            (intent.extras?.get("position") as? Number)?.toLong() ?: 0L
        }

        val mediaItems = entries.map { entry ->
            MediaItem.Builder()
                .setUri(entry.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
                .build()
        }

        releasePlayer()
        val exo = createPlayer(parseHeaders(intent))
        player = exo
        playerView.player = exo
        exo.setMediaItems(mediaItems, startIndex, startPosition)
        exo.prepare()
        exo.playWhenReady = true

        val message = if (entries.size > 1) {
            "TVPlayer : ${entries.size} éléments reçus"
        } else {
            "TVPlayer : un seul élément reçu, suivant/précédent indisponibles " +
                "(extras : ${intent.extras?.keySet()?.joinToString() ?: "aucun"})"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Accepte les conventions courantes des lecteurs externes (MX Player / VLC) :
     * - data : l'URL à lire
     * - video_list : liste d'URL (Uri ou String) pour enchaîner les épisodes
     * - video_list.name / titles : titres associés
     */
    private fun parsePlaylist(intent: Intent): List<Entry> {
        val entries = mutableListOf<Entry>()
        val names = intent.getStringArrayExtra("video_list.name")
            ?: intent.getStringArrayExtra("titles")

        @Suppress("DEPRECATION")
        val parcels = try {
            intent.getParcelableArrayExtra("video_list")
        } catch (e: Exception) {
            null
        }
        if (parcels != null) {
            parcels.forEachIndexed { i, p ->
                (p as? Uri)?.let { entries += Entry(it, names?.getOrNull(i)) }
            }
        } else {
            var strings: Array<String>? = try {
                intent.getStringArrayExtra("video_list")
            } catch (e: Exception) {
                null
            }
            if (strings == null) {
                strings = intent.getStringArrayListExtra("playlist")?.toTypedArray()
            }
            strings?.forEachIndexed { i, s ->
                entries += Entry(Uri.parse(s), names?.getOrNull(i))
            }
        }

        val data = intent.data
        if (entries.isEmpty() && data != null) {
            entries += Entry(data, intent.getStringExtra("title"))
        }
        return entries
    }

    /** Les en-têtes HTTP sont transmis par paires clé/valeur dans l'extra "headers" (convention MX Player). */
    private fun parseHeaders(intent: Intent): Map<String, String> {
        val raw = intent.getStringArrayExtra("headers") ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i + 1 < raw.size) {
            map[raw[i]] = raw[i + 1]
            i += 2
        }
        return map
    }

    // ------------------------------------------------------------------ Lecteur

    private fun createPlayer(headers: Map<String, String>): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)

        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(delayProcessor))
                    .build()
            }
        }

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage("fr")
            .build()

        endedByCompletion = false
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val title = mediaItem?.mediaMetadata?.title?.toString()
                if (!title.isNullOrBlank()) {
                    txtTitle.text = title
                    txtTitle.visibility = View.VISIBLE
                    handler.removeCallbacks(hideTitle)
                    handler.postDelayed(hideTitle, 4000)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    endedByCompletion = true
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val p = player ?: return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                } else {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Erreur de lecture : ${error.errorCodeName}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e(TAG, "Erreur de lecture", error)
                }
            }
        })
        return exo
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    // ------------------------------------------------------------------ Décalage audio

    private fun changeDelay(delta: Int) {
        val value = (delayProcessor.targetDelayMs + delta).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        delayProcessor.targetDelayMs = value
        prefs().edit().putInt(KEY_DELAY, value).apply()
        updateDelayLabel()
        showInfo(delayText(value))
    }

    private fun delayText(value: Int): String {
        val sign = if (value > 0) "+" else ""
        return "Décalage audio : $sign$value ms"
    }

    private fun updateDelayLabel() {
        txtDelay.text = delayText(delayProcessor.targetDelayMs)
    }

    private fun showInfo(text: String) {
        txtInfo.text = text
        txtInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 1500)
    }

    private fun prefs() = getSharedPreferences("tvplayer", MODE_PRIVATE)

    // ------------------------------------------------------------------ Télécommande

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val p = player
            val controllerVisible = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    p?.seekToNextMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    p?.seekToPreviousMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_LEFT_BRACKET -> {
                    changeDelay(-STEP_MS)
                    return true
                }
                KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                    changeDelay(STEP_MS)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    playerView.showController()
                    btnPlus.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!controllerVisible && p != null) {
                    p.seekBack()
                    showInfo("-10 s")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controllerVisible && p != null) {
                    p.seekForward()
                    showInfo("+10 s")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (controllerVisible && !delayPanel.hasFocus()) {
                    btnPlus.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerView.isControllerFullyVisible) {
            playerView.hideController()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Renvoie la position de lecture à l'application appelante (convention MX Player / VLC). */
    override fun finish() {
        val result = Intent()
        val p = player
        if (p != null) {
            val position = p.currentPosition
            val duration = if (p.duration == C.TIME_UNSET) 0L else p.duration
            result.putExtra("position", position.toInt())
            result.putExtra("duration", duration.toInt())
            result.putExtra("extra_position", position)
            result.putExtra("extra_duration", duration)
            result.putExtra("end_by", if (endedByCompletion) "playback_completion" else "user")
        }
        setResult(RESULT_OK, result)
        super.finish()
    }

    private companion object {
        const val TAG = "TVPlayer"
        const val KEY_DELAY = "audio_delay_ms"
        const val STEP_MS = 50
        const val MAX_DELAY_MS = 5000
        const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    }
}
