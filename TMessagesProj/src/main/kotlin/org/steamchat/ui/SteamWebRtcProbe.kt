package org.steamchat.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.steamchat.domain.SteamWebRtcProbeEvent
import org.steamchat.domain.normalizeSteamWebRtcOfferSdp
import org.steamchat.service.SteamService
import org.steamchat.voice.SteamWebRtcNative
import org.telegram.messenger.NativeLoader
import org.telegram.messenger.Utilities
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** One foreground-only call experiment. Closing its dialog releases microphone and call resources. */
internal object SteamWebRtcProbe {
    private const val TAG = "SteamVoiceProbe"
    private val mutex = Mutex()
    private var initialized = false

    @Volatile
    private var activeRoute: ProbeAudioRoute? = null
    private var activeMicrophone: AudioTrack? = null
    private var microphoneEnabled = true

    /** Flips the loudspeaker mid-call; null when no call is running. */
    fun toggleSpeaker(): Boolean? = activeRoute?.toggleSpeaker()

    /** Enables/disables the actual WebRTC sender track; null before a call reaches media setup. */
    @Synchronized
    fun toggleMicrophone(): Boolean? {
        val microphone = activeMicrophone ?: return null
        microphoneEnabled = !microphoneEnabled
        microphone.setEnabled(microphoneEnabled)
        return microphoneEnabled
    }

    suspend fun run(
        context: Context,
        service: SteamService,
        partnerSteamId64: Long,
        incomingVoiceChatId: Long? = null,
        /** Fired on the main thread the moment media is actually up - the call screen's clock. */
        onConnected: () -> Unit = {},
        progress: (String) -> Unit,
    ): String =
        withContext(Dispatchers.Default) {
            check(mutex.tryLock()) { "Проверка уже запущена" }
            var factory: PeerConnectionFactory? = null
            var peer: PeerConnection? = null
            var source: AudioSource? = null
            var track: AudioTrack? = null
            var audio: JavaAudioDeviceModule? = null
            val route = ProbeAudioRoute(context)
            val remoteAudioTracks = AtomicInteger()
            val appliedRemoteDescriptions = AtomicInteger()
            val playout = AtomicReference("не запущен")
            // Without this header extension libwebrtc may report audioLevel/totalAudioEnergy as 0
            // even for perfectly normal audio - so a zero level only means silence if it is there.
            val levelExtension = AtomicReference("?")
            val partnerSsrc = AtomicReference("?")
            val partnerVoiceStatus = AtomicReference("не прислан")
            suspend fun report(message: String) {
                Log.i(TAG, message)
                withContext(Dispatchers.Main) { progress(message) }
            }
            try {
                coroutineScope {
                        report("1/4 · Инициализация WebRTC…")
                        val app = context.applicationContext
                        // Not on Dispatchers.Main: AudioManager.setMode() is documented to block,
                        // and none of these calls need the main thread.
                        route.acquire()
                        activeRoute = route
                        Log.i(TAG, "Audio route: ${route.describe()}")
                        if (!initialized) {
                            NativeLoader.initNativeLibs(app)
                            check(NativeLoader.loaded()) { "Нативная библиотека не загружена" }
                            SteamWebRtcNative.initialize()
                            PeerConnectionFactory.initialize(
                                PeerConnectionFactory.InitializationOptions.builder(app)
                                    .setNativeLibraryName("tmessages.49")
                                    .setNativeLibraryLoader { NativeLoader.loaded() }
                                    .createInitializationOptions(),
                            )
                            initialized = true
                        }
                        // Playout state straight from the device module: packets arriving while the
                        // output AudioTrack never started (or failed to start) looks exactly like
                        // "no sound", and nothing else in the pipeline reports that.
                        val audioBuilder = JavaAudioDeviceModule.builder(app)
                            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                                override fun onWebRtcAudioTrackStart() {
                                    playout.set("играет")
                                    Log.i(TAG, "AudioTrack started")
                                }
                                override fun onWebRtcAudioTrackStop() {
                                    playout.set("остановлен")
                                    Log.i(TAG, "AudioTrack stopped")
                                }
                            })
                            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                                override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                                    playout.set("ошибка инициализации")
                                    Log.e(TAG, "AudioTrack init error: $errorMessage")
                                }
                                override fun onWebRtcAudioTrackStartError(
                                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                                    errorMessage: String,
                                ) {
                                    playout.set("ошибка старта: $errorCode")
                                    Log.e(TAG, "AudioTrack start error: $errorCode $errorMessage")
                                }
                                override fun onWebRtcAudioTrackError(errorMessage: String) {
                                    playout.set("ошибка: $errorMessage")
                                    Log.e(TAG, "AudioTrack error: $errorMessage")
                                }
                            })
                        audio = audioBuilder.createAudioDeviceModule()
                        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
                        val connected = CompletableDeferred<Unit>()
                        val serverConnected = CompletableDeferred<Unit>()
                        val callResponse = CompletableDeferred<Boolean>()
                        val failure = CompletableDeferred<Nothing>()
                        /** Normal end of the call, as opposed to [failure] - not an error. */
                        val hangup = CompletableDeferred<String>()
                        val notifications = launch(start = CoroutineStart.UNDISPATCHED) {
                            service.observeWebRtcProbeEvents().collect { event ->
                                when (event) {
                                    is SteamWebRtcProbeEvent.Connected -> {
                                        Log.i(TAG, "Steam session connected (SSRC matched)")
                                        serverConnected.complete(Unit)
                                    }
                                    is SteamWebRtcProbeEvent.OneOnOneResponse -> if (event.partnerSteamId64 == partnerSteamId64) {
                                        callResponse.complete(event.accepted)
                                    }
                                    is SteamWebRtcProbeEvent.RemoteDescriptionUpdated -> {
                                        runCatching {
                                            val update = JSONObject(event.descriptionJson)
                                            val type = SessionDescription.Type.fromCanonicalForm(update.getString("type"))
                                            val sdp = update.getString("sdp")
                                            levelExtension.set(if (sdp.contains("ssrc-audio-level")) "есть" else "нет")
                                            Log.i(TAG, "Remote $type v${event.version} direction=${audioDirection(sdp)} ssrc=${Regex("(?m)^a=ssrc:").findAll(sdp).count()}")
                                            awaitSdp { checkNotNull(peer).setRemoteDescription(it, SessionDescription(type, sdp)) }
                                            appliedRemoteDescriptions.incrementAndGet()
                                            // Whose stream is whose, so a silent SSRC can be named.
                                            event.ssrcOwners.entries
                                                .firstOrNull { it.value == (partnerSteamId64 and 0xffffffffL) }
                                                ?.let { partnerSsrc.set(it.key.toString()) }
                                            if (type == SessionDescription.Type.OFFER) {
                                                val answer = checkNotNull(awaitSdp { checkNotNull(peer).createAnswer(it, MediaConstraints()) })
                                                awaitSdp { checkNotNull(peer).setLocalDescription(it, answer) }
                                                if (event.version > 0) service.acknowledgeWebRtcProbeUpdate(event.version)
                                                Log.i(TAG, "Applied remote SDP offer version=${event.version}")
                                            }
                                        }.onFailure { failure.completeExceptionally(it) }
                                    }
                                    is SteamWebRtcProbeEvent.PartnerVoiceStatus -> {
                                        partnerVoiceStatus.set(
                                            when {
                                                event.hasNoMic -> "Steam: у партнёра нет микрофона"
                                                event.muted -> "Steam: микрофон партнёра выключен"
                                                else -> "Steam: микрофон партнёра включён (${event.sampleRate} Гц)"
                                            },
                                        )
                                    }
                                    is SteamWebRtcProbeEvent.CallEnded -> {
                                        // Confirmed live: ending a Steam call ends it for everyone,
                                        // so this is a real hangup, not someone stepping out of a
                                        // room that stays open - the session has to stop with it.
                                        Log.i(TAG, "Voice chat ended by Steam: ${event.voiceChatId}")
                                        hangup.complete("Собеседник завершил звонок.")
                                    }
                                    is SteamWebRtcProbeEvent.Disconnected -> {
                                        // Steam's own words when it gave any, never an invented cause.
                                        failure.completeExceptionally(
                                            IllegalStateException(event.reason ?: "Соединение со Steam прервано"),
                                        )
                                    }
                                }
                            }
                        }
                        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                            // This libwebrtc's Plan B ignores Steam's second audio m-line (the peer).
                            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                        }
                        peer = checkNotNull(factory!!.createPeerConnection(config, object : PeerConnection.Observer {
                            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                                Log.i(TAG, "PeerConnection: $state")
                                if (state == PeerConnection.PeerConnectionState.CONNECTED) connected.complete(Unit)
                                if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                                    failure.completeExceptionally(IllegalStateException("WebRTC: $state"))
                                }
                            }
                            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { Log.i(TAG, "ICE: $state") }
                            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                            override fun onIceCandidate(candidate: IceCandidate) = Unit
                            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                            override fun onAddStream(stream: MediaStream) {
                                stream.audioTracks.firstOrNull()?.apply { setEnabled(true); setVolume(1.0) }
                                remoteAudioTracks.addAndGet(stream.audioTracks.size)
                                Log.i(TAG, "Remote stream audioTracks=${stream.audioTracks.size}")
                            }
                            override fun onTrack(transceiver: RtpTransceiver) {
                                (transceiver.receiver.track() as? AudioTrack)?.apply {
                                    setEnabled(true)
                                    setVolume(1.0)
                                    remoteAudioTracks.incrementAndGet()
                                    Log.i(TAG, "Remote audio track mid=${transceiver.mid}")
                                }
                            }
                            override fun onRemoveStream(stream: MediaStream) = Unit
                            override fun onDataChannel(channel: DataChannel) = Unit
                            override fun onRenegotiationNeeded() = Unit
                        })) { "Не удалось создать PeerConnection" }
                        val pc = peer!!
                        pc.setAudioRecording(true)
                        pc.setAudioPlayout(true)
                        source = factory!!.createAudioSource(MediaConstraints())
                        track = factory!!.createAudioTrack("steam-probe-audio", source).also { it.setEnabled(true) }
                        synchronized(this@SteamWebRtcProbe) {
                            activeMicrophone = track
                            microphoneEnabled = true
                        }
                        pc.addTrack(track, listOf("steam-probe"))
                        val offerConstraints = MediaConstraints().apply {
                            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
                            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
                            optional += MediaConstraints.KeyValuePair("VoiceActivityDetection", "true")
                        }
                        val createdOffer = checkNotNull(awaitSdp { pc.createOffer(it, offerConstraints) })
                        val offer = SessionDescription(
                            SessionDescription.Type.OFFER,
                            normalizeSteamWebRtcOfferSdp(createdOffer.description),
                        )
                        Log.i(
                            TAG,
                            "Offer audio=${offer.description.contains("m=audio ")} " +
                                "sendrecv=${offer.description.contains("a=sendrecv")} " +
                                "opus=${offer.description.contains("opus/", ignoreCase = true)} " +
                                "ssrc=${Regex("(?m)^a=ssrc:").findAll(offer.description).count()}",
                        )
                        awaitSdp { pc.setLocalDescription(it, offer) }
                        report("2/4 · Отправка параметров соединения Steam…")
                        val offerJson = JSONObject().put("type", "offer").put("sdp", offer.description).toString()
                        // Steam's own web client sends UAParser's browser identity. Use the exact
                        // Chromium build shipped by the installed desktop Steam client.
                        val answerJson = service.initiateWebRtcProbe(offerJson, "Chrome", "126.0.6478.183")
                        report("3/4 · Steam вернул SDP-ответ. Подключение…")
                        val answer = JSONObject(answerJson)
                        check(answer.getString("type") == "answer") { "Steam вернул неожиданный тип SDP" }
                        val answerSdp = answer.getString("sdp")
                        Log.i(TAG, "Answer codec=${audioCodec(answerSdp)} direction=${audioDirection(answerSdp)}")
                        awaitSdp { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answerSdp)) }
                        val ready = async { connected.await(); serverConnected.await() }
                        select<Unit> {
                            failure.onAwait { }
                            ready.onAwait { }
                        }
                        check(pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) { "WebRTC отключился до завершения проверки" }
                        report("4/5 · WebRTC подключён, Steam подтвердил сессию")
                        if (incomingVoiceChatId == null) {
                            service.requestOneOnOneWebRtcProbe(partnerSteamId64)
                            report("5/5 · Входящий звонок отправлен. Ожидание ответа…")
                        } else {
                            service.joinOneOnOneWebRtcProbe(incomingVoiceChatId, partnerSteamId64)
                            report("5/5 · Присоединение к входящему звонку…")
                        }
                        val accepted = withTimeout(60_000) {
                            select<Boolean> {
                                failure.onAwait { false }
                                // Caller hung up while it was still ringing - no point waiting out
                                // the full minute for an answer that is not coming.
                                hangup.onAwait { false }
                                callResponse.onAwait { it }
                            }
                        }
                        if (accepted) {
                            // Re-assert the route only now: starting a voice-communication AudioTrack
                            // makes the platform re-evaluate output, which can drop a device chosen
                            // before playout existed (and setMode() itself lands asynchronously).
                            route.applySpeaker(route.speakerOn)
                            withContext(Dispatchers.Main) { onConnected() }
                            report("Звонок соединён. Вывод: ${route.describe()}.")
                            // Live RTP counters, because "не слышно" has two completely different
                            // causes: audio that never arrives (signalling/SFU) and audio that
                            // arrives but is not played (routing/volume). Only stats tell them apart.
                            val stats = launch {
                                while (true) {
                                    delay(2_000)
                                    report(
                                        "Вывод: ${route.describe()}, воспроизведение: ${playout.get()}\n" +
                                            "${audioStats(pc)}\n" +
                                            "SDP: ${appliedRemoteDescriptions.get()}, потоков ${remoteAudioTracks.get()}, " +
                                            "hdrext ${levelExtension.get()}, ssrc партнёра ${partnerSsrc.get()}\n" +
                                            partnerVoiceStatus.get(),
                                    )
                                }
                            }
                            val outcome = select<String> {
                                failure.onAwait { it }
                                hangup.onAwait { it }
                            }
                            // coroutineScope waits for every child, and both of these run forever:
                            // a normal end has to stop them by hand. Only the failure path did it
                            // implicitly, by throwing and cancelling the scope - which is why a
                            // hangup left the call running while an error ended it correctly.
                            stats.cancel()
                            notifications.cancel()
                            outcome
                        } else if (incomingVoiceChatId == null) {
                            notifications.cancel()
                            "Второй аккаунт отклонил звонок. Входящий вызов и сигнализация работают."
                        } else {
                            error("Steam не разрешил присоединиться к входящему звонку")
                        }
                }
            } finally {
                synchronized(this@SteamWebRtcProbe) { activeMicrophone = null }
                fun cleanup(name: String, action: () -> Unit) {
                    runCatching(action).onFailure { Log.e(TAG, "Cleanup $name: ${it.javaClass.simpleName}") }
                }
                cleanup("peer") { peer?.dispose() }
                cleanup("track") { track?.dispose() }
                cleanup("source") { source?.dispose() }
                cleanup("factory") { factory?.dispose() }
                cleanup("audio") { audio?.release() }
                cleanup("session") { service.cancelWebRtcProbe() }
                activeRoute = null
                route.release()
                mutex.unlock()
                Log.i(TAG, "Probe resources released")
            }
        }

    private suspend fun awaitSdp(start: (SdpObserver) -> Unit): SessionDescription? {
        val result = CompletableDeferred<SessionDescription?>()
        start(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { result.complete(sdp) }
            override fun onSetSuccess() { result.complete(null) }
            override fun onCreateFailure(error: String) { result.completeExceptionally(IllegalStateException("WebRTC create SDP: $error")) }
            override fun onSetFailure(error: String) { result.completeExceptionally(IllegalStateException("WebRTC set SDP: $error")) }
        })
        return result.await()
    }

    /**
     * Output routing for the call. WebRtcAudioTrack plays with USAGE_VOICE_COMMUNICATION (see
     * org/webrtc/audio/WebRtcAudioTrack.java), which a real phone sends to the *earpiece* unless the
     * loudspeaker is selected explicitly - an emulator has a single output and no earpiece, which is
     * exactly why the call was audible there and silent in the hand. The choice is also re-asserted
     * once audio is really flowing, because starting a voice-communication AudioTrack makes the
     * platform re-evaluate routing and can drop a device selected before playout existed.
     */
    private class ProbeAudioRoute(context: Context) {
        private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val focusListener = AudioManager.OnAudioFocusChangeListener { }
        private var previousMode = AudioManager.MODE_NORMAL
        private var previousSpeakerphone = false
        private var previousCommunicationDevice: AudioDeviceInfo? = null
        private var previousCallVolume = 0
        private var raisedCallVolume = -1
        private var focusGranted = false
        private var acquired = false

        @Volatile
        var speakerOn = true
            private set

        fun acquire() {
            previousMode = audioManager.mode
            previousSpeakerphone = audioManager.isSpeakerphoneOn
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousCommunicationDevice = audioManager.communicationDevice
            }
            acquired = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            focusGranted = audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            // The rocker only moves the voice-call stream while a call is up, so whatever this
            // phone is parked at is what the call starts with - live testing found 3/15, quiet
            // enough to read as "no sound at all". Lifted to clearly audible, never lowered, and
            // put back on release unless the user moved it themselves meanwhile.
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val wanted = (max * 0.7).roundToInt().coerceIn(1, max)
            previousCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (previousCallVolume < wanted) {
                runCatching {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, wanted, 0)
                    raisedCallVolume = wanted
                }.onFailure { Log.e(TAG, "Raise call volume: ${it.javaClass.simpleName}") }
            }
            applySpeaker(true)
        }

        fun applySpeaker(on: Boolean): Boolean {
            speakerOn = on
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
                    ?.let { if (audioManager.setCommunicationDevice(it)) return true }
            }
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = on
            return audioManager.isSpeakerphoneOn == on
        }

        /** Flips output; the slow legacy setter runs off the caller's thread, as Telegram's own does. */
        fun toggleSpeaker(): Boolean {
            val target = !speakerOn
            speakerOn = target
            Utilities.globalQueue.postRunnable { applySpeaker(target) }
            return target
        }

        /** Route and level in words, so a silent call can be diagnosed from the phone itself. */
        fun describe(): String {
            val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (audioManager.communicationDevice?.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "динамик"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "разговорный динамик"
                    null -> "не выбран"
                    else -> "устройство ${audioManager.communicationDevice?.type}"
                }
            } else if (audioManager.isSpeakerphoneOn) {
                "динамик"
            } else {
                "разговорный динамик"
            }
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val focus = if (focusGranted) "" else ", без аудио-фокуса"
            return "$route, громкость $volume/${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}$focus"
        }

        fun release() {
            if (!acquired) return
            acquired = false
            Utilities.globalQueue.postRunnable {
                runCatching {
                    // Only undo our own change: if the user worked the rocker during the call,
                    // that is their choice and it stays.
                    if (raisedCallVolume >= 0 && audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == raisedCallVolume) {
                        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previousCallVolume, 0)
                    }
                    if (focusGranted) audioManager.abandonAudioFocus(focusListener)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
                    audioManager.mode = previousMode
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (previousMode == AudioManager.MODE_IN_COMMUNICATION) {
                            previousCommunicationDevice?.let(audioManager::setCommunicationDevice)
                        }
                    } else {
                        audioManager.isSpeakerphoneOn = previousSpeakerphone
                    }
                }.onFailure { Log.e(TAG, "Restore audio route: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Direction of the audio m-line, i.e. whether the other end intends to send anything at all. */
    private fun audioDirection(sdp: String): String =
        listOf("sendrecv", "recvonly", "sendonly", "inactive").firstOrNull { sdp.contains("a=$it") } ?: "не указано"

    /**
     * Real inbound/outbound RTP counters straight from the PeerConnection. `onAddStream` alone is
     * not evidence: it is a Plan-B-only callback, so it can stay silent while audio flows normally.
     */
    private suspend fun audioStats(pc: PeerConnection): String {
        val pending = CompletableDeferred<RTCStatsReport>()
        pc.getStats { pending.complete(it) }
        val report = withTimeoutOrNull(3_000) { pending.await() } ?: return "статистика недоступна"
        var sent = 0L
        val codecs = mutableSetOf<String>()
        // Per stream, not summed: this call carries two remote audio streams, and an aggregate
        // hides the case where the voice is on one of them while the other is silence.
        val inbound = mutableListOf<String>()
        fun count(value: Any?) = (value as? Number)?.toLong() ?: 0L
        fun decimal(value: Any?) = (value as? Number)?.let { "%.4f".format(it.toDouble()) } ?: "—"
        report.statsMap.values.forEach { entry ->
            val members = entry.members
            val audio = members["kind"] == "audio" || members["mediaType"] == "audio"
            when {
                entry.type == "inbound-rtp" && audio -> inbound += "ssrc ${count(members["ssrc"])}: " +
                    "${count(members["packetsReceived"])} пак, ур ${decimal(members["audioLevel"])}, " +
                    "эн ${decimal(members["totalAudioEnergy"])}, " +
                    "сэмп ${count(members["totalSamplesReceived"])}/зг ${count(members["concealedSamples"])}"
                entry.type == "outbound-rtp" && audio -> sent += count(members["packetsSent"])
                entry.type == "codec" -> (members["mimeType"] as? String)?.let(codecs::add)
            }
        }
        if (inbound.isEmpty()) return "звук со Steam НЕ приходит · отправлено $sent"
        return "отправлено $sent пак., кодеки ${codecs.joinToString().ifEmpty { "?" }}\n" + inbound.joinToString("\n")
    }

    private fun audioCodec(sdp: String): String {
        val payload = sdp.lineSequence().firstOrNull { it.startsWith("m=audio ") }
            ?.split(' ')?.getOrNull(3) ?: return "unknown"
        return sdp.lineSequence().firstOrNull { it.startsWith("a=rtpmap:$payload ") }
            ?.substringAfter(' ') ?: payload
    }

}
