package com.mozkhan.shop.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Scanner-style beep + haptics.
 * The classic supermarket "beep" is a short, bright ~2.7 kHz tone, so we synthesise exactly that
 * once as PCM and replay it through a static AudioTrack (instant, no latency, no allocation per scan).
 */
class Feedback(ctx: Context, private val prefs: Prefs) {
    private val ok: AudioTrack? = runCatching { track(tones(listOf(Tone(2750.0, 110)))) }.getOrNull()
    private val bad: AudioTrack? = runCatching { track(tones(listOf(Tone(420.0, 140), Tone(0.0, 40), Tone(320.0, 200)))) }.getOrNull()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private class Tone(val hz: Double, val ms: Int)

    private fun tones(list: List<Tone>): ShortArray {
        val rate = RATE
        val out = ArrayList<Short>()
        for (t in list) {
            val n = rate * t.ms / 1000
            for (i in 0 until n) {
                val env = minOf(1.0, i / 120.0, (n - i) / 240.0).coerceAtLeast(0.0) // soft attack / release, no clicks
                val s = if (t.hz == 0.0) 0.0 else sin(2 * PI * t.hz * i / rate) * 0.85 * env
                out.add((s * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return out.toShortArray()
    }

    private fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private fun play(t: AudioTrack?) {
        if (t == null || !prefs.sound.value) return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    /** microphone opens (rising) / closes (falling): the user knows without looking at the screen */
    private val micOn: AudioTrack? = runCatching { track(tones(listOf(Tone(880.0, 70), Tone(0.0, 20), Tone(1320.0, 90)))) }.getOrNull()
    private val micOff: AudioTrack? = runCatching { track(tones(listOf(Tone(1320.0, 70), Tone(0.0, 20), Tone(880.0, 90)))) }.getOrNull()
    fun listening(on: Boolean) { play(if (on) micOn else micOff); buzz(if (on) 25 else 15) }

    fun success() { play(ok); buzz(35) }
    fun error() { play(bad); buzz(120) }
    fun tick() = buzz(12)

    private fun buzz(ms: Long) {
        if (!prefs.vibrate.value) return
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    companion object { private const val RATE = 22050 }
}
