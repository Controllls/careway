package com.example.careway.vehicle

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import java.util.Locale

class VoiceGuide(private val context: Context, private val report: (String) -> Unit) {
    private var pleos: PleosTts? = null
    private var ready = false
    private var closed = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val listener = object : EventListener {
        override fun onStart() { if (!closed) report("Pleos 음성 합성 시작") }
        override fun onReady() {}
        override fun onDone() { if (!closed) report("Pleos 음성 합성 완료") }
        override fun onStop() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onError(errorType: ErrorType) { report("음성 안내 오류: ${errorType.name}") }
    }
    private var generation = 0
    init { retry() }
    fun retry() {
        if (closed) return
        val session = ++generation
        ready = false
        runCatching { pleos?.removeEventListener(listener); pleos?.release() }
        pleos = null
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            runCatching {
                pleos = PleosTts(context, Mode.ON_DEVICE).also { tts ->
                    tts.initialize(object : OnInitializeListener {
                        override fun onSuccess() {
                            if (closed || session != generation) return
                            // The engine expects a region-qualified locale (ko-KR), not language-only ko.
                            runCatching { tts.setLocale(Locale.KOREA); tts.setAudioAttributes(attributes); tts.addEventListener(listener) }
                                .onSuccess { ready = true; report("Pleos 온디바이스 음성 준비됨") }
                                .onFailure { report("음성 초기화 실패: ${it.message}") }
                        }
                        override fun onFailure(errorType: ErrorType) { report("음성 사용 불가: ${errorType.name}") }
                    })
                }
            }.onFailure { report("음성 서비스 연결 실패: ${it.message}") }
        } else report("Pleos 음성 서비스가 없는 기기 · 화면 안내 사용")
    }

    fun speak(text: String) {
        if (!ready || closed) return
        runCatching {
            pleos?.let { it.stop(); it.speak(text) }
            report("Pleos 음성 요청 전송")
        }.onFailure { report("음성 재생 실패: ${it.message}") }
    }
    fun stop() { runCatching { pleos?.stop() } }
    fun close() {
        closed = true; ready = false; generation++
        runCatching { pleos?.stop(); pleos?.removeEventListener(listener); pleos?.release() }
    }
}
