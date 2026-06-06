package com.earlyspark.orbn.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log

/**
 * Device-agnostic audio-output capability probe (M3 bit-perfect/hi-res spike).
 *
 * orbn deliberately never addresses a specific DAC chip (e.g. the iKKO MindOne's CS43198)
 * directly — that needs root/vendor SDKs and would lock us to one device. Instead this asks
 * Android, via standard APIs, what the *currently connected* output devices support and
 * whether any offers a BIT_PERFECT mixer path. On the MindOne the hi-res sink is the CS43198
 * (exposed as a digital dock); on another phone it might be a USB-C dongle — same query, same
 * code. The "best" device is simply whatever reports the highest rate / a bit-perfect path.
 */
object AudioCapabilities {
    const val TAG = "OrbnAudioCaps"

    /** Build a human-readable capability report for all output devices and log it. */
    fun report(am: AudioManager): String {
        val sb = StringBuilder()
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        sb.append("Output devices (").append(outs.size).append("):\n")
        for (d in outs) {
            val rates = if (d.sampleRates.isNotEmpty()) d.sampleRates.joinToString(",") else "any/unspecified"
            val encs = if (Build.VERSION.SDK_INT >= 31 && d.encodings.isNotEmpty())
                d.encodings.joinToString(",") { encName(it) } else "unspecified"
            val maxCh = d.channelCounts.maxOrNull() ?: 0
            sb.append("  • ").append(typeName(d.type)).append("  \"").append(d.productName).append("\"\n")
            sb.append("      rates=[").append(rates).append("]Hz  encodings=[").append(encs)
                .append("]  maxCh=").append(maxCh).append('\n')

            // Definitive bit-perfect check (Android 14 / API 34+).
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    val attrs: List<AudioMixerAttributes> = am.getSupportedMixerAttributes(d)
                    if (attrs.isEmpty()) {
                        sb.append("      mixerAttrs: none offered\n")
                    } else {
                        for (a in attrs) {
                            val f = a.format
                            val bp = if (a.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                                "BIT_PERFECT" else "DEFAULT"
                            sb.append("      mixerAttr: ").append(bp).append(' ')
                                .append(f.sampleRate).append("Hz ").append(encName(f.encoding))
                                .append(" ch=").append(f.channelCount).append('\n')
                        }
                    }
                } catch (t: Throwable) {
                    sb.append("      mixerAttrs: query failed (").append(t.message).append(")\n")
                }
            } else {
                sb.append("      mixerAttrs: needs API 34+ (have ").append(Build.VERSION.SDK_INT).append(")\n")
            }
        }
        val report = sb.toString()
        Log.i(TAG, "\n$report")
        return report
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "type$type"
    }

    private fun encName(enc: Int): String = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCMfloat"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM8"
        else -> "enc$enc"
    }
}
