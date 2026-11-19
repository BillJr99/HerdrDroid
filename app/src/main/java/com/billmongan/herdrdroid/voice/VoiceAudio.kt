package com.billmongan.herdrdroid.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

internal enum class VoiceRoute(val label: String) {
    PHONE("Phone speaker and mic"),
    BLUETOOTH("Bluetooth speakers and mic"),
}

internal fun voiceRoute(context: Context): VoiceRoute {
    val saved = context.getSharedPreferences("voice_control", Context.MODE_PRIVATE).getString("audio_route", null)
    return VoiceRoute.entries.firstOrNull { it.name == saved } ?: VoiceRoute.PHONE
}

internal fun VoiceRoute.matchesMic(type: Int): Boolean = when (this) {
    VoiceRoute.PHONE -> type == AudioDeviceInfo.TYPE_BUILTIN_MIC
    VoiceRoute.BLUETOOTH -> type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
}

/** Keep the communication route until both speech and the hands-free recorder have released it. */
internal object VoiceAudio {
    private var users = 0
    private var current: Lease? = null

    class Lease internal constructor(
        private val manager: AudioManager,
        internal val route: VoiceRoute,
        private val deviceId: Int?,
        private val previousMode: Int,
    ) {
        private var closed = false
        val communication: Boolean get() = deviceId != null
        val attributes: AudioAttributes
            get() = AudioAttributes.Builder()
                .setUsage(if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        fun routed(): Boolean = if (Build.VERSION.SDK_INT >= 31 && deviceId != null) {
            manager.communicationDevice?.id == deviceId
        } else true

        internal fun releaseDevice() {
            if (Build.VERSION.SDK_INT >= 31 && deviceId != null) {
                manager.clearCommunicationDevice()
                if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = previousMode
            }
        }

        fun close() = synchronized(VoiceAudio) {
            if (!closed) {
                closed = true
                users--
                if (users == 0) {
                    val old = current
                    current = null
                    old?.releaseDevice()
                }
            }
        }

        internal fun share() = Lease(manager, route, deviceId, previousMode)
    }

    @Synchronized
    fun acquire(context: Context, route: VoiceRoute): Lease {
        current?.let {
            check(it.route == route) { "Stop the current voice session before switching audio." }
            users++
            return it.share()
        }
        val manager = context.getSystemService(AudioManager::class.java)
        val previousMode = manager.mode
        var id: Int? = null
        if (Build.VERSION.SDK_INT >= 31) {
            val matching = manager.availableCommunicationDevices.filter {
                if (route == VoiceRoute.PHONE) it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                else it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            val device = matching.firstOrNull { it.id == manager.communicationDevice?.id } ?: matching.firstOrNull()
            checkNotNull(device) {
                if (route == VoiceRoute.BLUETOOTH) "Connect a Bluetooth device with Phone calls enabled."
                else "The phone speaker is unavailable for voice."
            }
            check(previousMode == AudioManager.MODE_NORMAL || previousMode == AudioManager.MODE_IN_COMMUNICATION) {
                "Voice audio is unavailable while another call is active."
            }
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            try {
                check(manager.setCommunicationDevice(device)) { "Android could not select the requested audio device." }
                id = device.id
            } catch (e: Exception) {
                manager.mode = previousMode
                throw e
            }
        } else check(route == VoiceRoute.PHONE) { "Bluetooth voice routing needs Android 12 or later." }
        return Lease(manager, route, id, previousMode).also {
            users = 1
            current = it
        }
    }
}
