package com.billmongan.herdrdroid.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.billmongan.herdrdroid.ui.Speaker
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** An explicitly started session. Recognition and wake detection stay on the phone. */
class HandsFreeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var generation = 0L
    private val recorderLock = Any()
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || command == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "Hands-free voice", NotificationManager.IMPORTANCE_LOW))
        if (!notificationManager.areNotificationsEnabled() ||
            notificationManager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            problem = "Enable Hands-free voice notifications so the Stop action stays available."
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = notification("Preparing offline voice recognition")
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, n)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HerdrDroid:voice").apply { acquire(SESSION_MS) }
        } catch (e: Exception) {
            problem = "Could not start the microphone. Open HerdrDroid and allow microphone access."
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        val session = ++generation
        active = true
        problem = null
        main.postDelayed({ stopSelf() }, SESSION_MS)
        worker = Thread({
            try {
                listen(session)
            } catch (e: Exception) {
                main.post {
                    if (isCurrent(session)) {
                        problem = "Hands-free voice stopped: ${e.message ?: "microphone unavailable"}"
                        stopSelf()
                    }
                }
            } catch (e: LinkageError) {
                main.post {
                    if (isCurrent(session)) {
                        problem = "Offline voice recognition is unavailable on this device."
                        stopSelf()
                    }
                }
            }
        }, "HerdrDroid voice").apply { start() }
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, HandsFreeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("HerdrDroid hands-free")
            .setContentText(message)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun isCurrent(session: Long) = running && session == generation

    private fun modelPath(session: Long): String = synchronized(modelLock) {
        val folder = File(filesDir, MODEL)
        val ready = File(folder, ".verified")
        if (ready.isFile && ready.readText() == SHA256) return folder.path
        val archive = File.createTempFile("voice-model", ".zip", cacheDir)
        try {
            val connection = URL("https://alphacephei.com/vosk/models/$MODEL.zip").openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.getInputStream().use { input ->
                archive.outputStream().use { output ->
                    val bytes = ByteArray(64 * 1024)
                    var size = 0
                    while (true) {
                        check(isCurrent(session)) { "Session stopped" }
                        val count = input.read(bytes)
                        if (count < 0) break
                        size += count
                        check(size <= 50 * 1024 * 1024) { "Model download too large" }
                        digest.update(bytes, 0, count)
                        output.write(bytes, 0, count)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "Model checksum mismatch" }
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    check(isCurrent(session)) { "Session stopped" }
                    val entry = zip.nextEntry ?: break
                    val file = File(filesDir, entry.name).canonicalFile
                    if (file.path.startsWith(folder.canonicalPath + File.separator)) {
                        if (entry.isDirectory) file.mkdirs()
                        else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            ready.writeText(SHA256)
            return folder.path
        } finally {
            archive.delete()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun listen(session: Long) {
        val path = modelPath(session)
        if (!isCurrent(session)) return
        Model(path).use { model ->
            Recognizer(model, RATE.toFloat(), """["hey herder","hey herd","[unk]"]""").use { wake ->
                Recognizer(model, RATE.toFloat()).use { speech ->
                    Recognizer(model, RATE.toFloat(), """["confirm answer","cancel answer","[unk]"]""").use { confirmation ->
                        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        check(minimum > 0) { "Unsupported microphone format" }
                        val mic = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE))
                        var registered = false
                        try {
                            synchronized(recorderLock) {
                                if (!isCurrent(session)) return
                                val builtin = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                                check(builtin != null && mic.setPreferredDevice(builtin)) { "The phone microphone could not be selected" }
                                recorder = mic
                                registered = true
                                mic.startRecording()
                                check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                            }
                            main.post { if (isCurrent(session)) notificationManager.notify(ID, notification("Listening for Hey Herdr")) }
                            val buffer = ShortArray(1600)
                            var deadline = 0L
                            var ignoreUntil = 0L
                            var wasSpeaking = false
                            var commandRecognizer = speech
                            val routingDeadline = android.os.SystemClock.elapsedRealtime() + 1_000L
                            while (isCurrent(session)) {
                                val count = mic.read(buffer, 0, buffer.size)
                                check(count >= 0) { "Microphone disconnected" }
                                if (count == 0 || !isCurrent(session)) continue
                                val route = mic.routedDevice
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (route == null && now < routingDeadline) continue
                                check(route?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { "The phone microphone is no longer active" }
                                if (Speaker.speakingKey != null) {
                                    if (!wasSpeaking) {
                                        wake.reset()
                                        commandRecognizer.reset()
                                        deadline = 0
                                    }
                                    wasSpeaking = true
                                    continue
                                }
                                if (wasSpeaking) {
                                    wasSpeaking = false
                                    ignoreUntil = now + 800L
                                }
                                if (now < ignoreUntil) continue
                                if (deadline == 0L) {
                                    if (!wake.acceptWaveForm(buffer, count)) continue
                                    if (resultText(wake.result) in setOf("hey herder", "hey herd")) {
                                        main.post { if (isCurrent(session)) playCue() }
                                        deadline = now + 7_000L
                                        ignoreUntil = now + 200L
                                        commandRecognizer = if (awaitingConfirmation?.invoke() == true) confirmation else speech
                                        commandRecognizer.reset()
                                    }
                                } else {
                                    val ended = commandRecognizer.acceptWaveForm(buffer, count)
                                    val said = if (ended) resultText(commandRecognizer.result) else if (now >= deadline) resultText(commandRecognizer.finalResult) else ""
                                    if (said.isNotBlank() || now >= deadline) {
                                        deadline = 0
                                        wake.reset()
                                        ignoreUntil = now + 600L
                                        if (said.isNotBlank()) main.post { if (isCurrent(session)) command?.invoke(said) }
                                    }
                                }
                            }
                        } finally {
                            synchronized(recorderLock) {
                                if (recorder === mic) {
                                    recorder = null
                                    closeRecorder(mic)
                                } else if (!registered) mic.release()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun closeRecorder(mic: AudioRecord) {
        runCatching { mic.stop() }
        mic.release()
    }

    private fun playCue() {
        val audio = getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val tone = runCatching { android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull()
        tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        Handler(Looper.getMainLooper()).postDelayed({
            tone?.release()
            audio.abandonAudioFocusRequest(focus)
        }, 200)
    }

    private fun resultText(json: String): String = JSONObject(json).optString("text").trim()

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        generation++
        synchronized(recorderLock) {
            recorder?.let(::closeRecorder)
            recorder = null
        }
        worker?.interrupt()
        main.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        active = false
        sessionEnded?.invoke()
        super.onDestroy()
    }

    companion object {
        private val modelLock = Any()
        private const val CHANNEL = "hands_free_voice"
        private const val ID = 20060
        private const val STOP = "com.billmongan.herdrdroid.STOP_VOICE"
        private const val RATE = 16_000
        private const val SESSION_MS = 60 * 60 * 1000L
        private const val MODEL = "vosk-model-small-en-us-0.15"
        private const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        @Volatile internal var awaitingConfirmation: (() -> Boolean)? = null
        internal var sessionEnded: (() -> Unit)? = null
        internal var command: ((String) -> Unit)? = null
        internal var active by mutableStateOf(false)
            private set
        internal var problem by mutableStateOf<String?>(null)
            private set
    }
}
