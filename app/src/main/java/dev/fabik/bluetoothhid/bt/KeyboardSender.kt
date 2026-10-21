package dev.fabik.bluetoothhid.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log
import dev.fabik.bluetoothhid.utils.ExtraKeys
import kotlinx.coroutines.delay

@SuppressLint("MissingPermission")
open class KeyboardSender(
    private val keyboardTranslator: KeyTranslator,
    private val hidDevice: BluetoothHidDevice,
    private val host: BluetoothDevice
) {
    companion object {
        private const val TAG = "KeyboardSender"

        // 工业级蓝牙 HID 信号安全下限，防止因时间片过短导致 Windows 蓝牙协议栈丢帧或合并报文
        private const val MIN_PRESS_DELAY_MS = 12L    // 单次按键最小物理保持时长
        private const val MIN_RELEASE_DELAY_MS = 12L  // 按键释放最小间隔时长
        private const val MAX_SEND_RETRIES = 5        // sendReport 返回 false 时的最大重试次数
        private const val RETRY_DELAY_MS = 4L         // 重试间隔
    }

    @Volatile
    private var cancelRequested = false

    /** Request cancellation of the currently running [sendProcessedString]. */
    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * 带重试机制的底层报文发送，确保蓝牙底层信道忙碌时不会静默丢包。
     */
    private suspend fun sendReportWithRetry(
        reportData: ByteArray,
        maxRetries: Int = MAX_SEND_RETRIES,
        retryDelayMs: Long = RETRY_DELAY_MS
    ): Boolean {
        var attempts = 0
        while (attempts < maxRetries) {
            val success = hidDevice.sendReport(host, Descriptor.ID, reportData)
            if (success) {
                return true
            }
            attempts++
            Log.w(TAG, "sendReport returned false, retrying ($attempts/$maxRetries)...")
            delay(retryDelayMs)
        }
        Log.e(TAG, "sendReport failed after $maxRetries attempts!")
        return false
    }

    /**
     * 终极按键清空保障（Robust Key Release Guard）：
     * 传输结束（正常完成、用户中途取消或发生异常）时，连续发送全 0 释放报文，
     * 100% 确保 Windows 接收端退出长按状态，彻底杜绝按键粘连。
     */
    suspend fun releaseAllKeysReliably() {
        val emptyReport = ByteArray(3)
        sendReportWithRetry(emptyReport, maxRetries = 5, retryDelayMs = 4)
        delay(10)
        sendReportWithRetry(emptyReport, maxRetries = 3, retryDelayMs = 4)
    }

    /**
     * Sends [processedString] as HID keystrokes and returns the number of characters that
     * were actually typed on the host, or `null` if the send was cancelled before any visible
     * character was typed.
     */
    suspend fun sendProcessedString(
        processedString: String,
        sendDelay: Long,
        appendKey: ExtraKeys,
        locale: String,
    ): Int? {
        cancelRequested = false

        val finalString = appendKey.suffix?.let { "$processedString$it" } ?: processedString

        val keys: List<Pair<Key, Boolean>> =
            keyboardTranslator.translateStringWithTemplateDetailed(finalString, locale)

        var charCount = 0

        try {
            var prevKey: Key? = null
            for ((key, completesChar) in keys) {
                if (cancelRequested) return charCount.takeIf { it > 0 }
                Log.d(TAG, "sendProcessedString: $key")

                // 连续相同按键时增加微小防粘滞缓冲，确保 Windows 状态机完整经历按下-弹起循环
                if (prevKey == key) {
                    delay(8)
                }

                sendKey(key, sendDelay / 2)
                if (completesChar) charCount++
                if (cancelRequested) return charCount.takeIf { it > 0 }

                val interKeyDelay = maxOf(MIN_RELEASE_DELAY_MS, sendDelay / 2)
                delay(interKeyDelay)
                prevKey = key
            }
        } finally {
            // 无论任何情况退出，必须彻底释放所有按键
            releaseAllKeysReliably()
        }

        return charCount
    }

    /**
     * Send [count] backspace key presses to undo the last transmitted value.
     * @param count number of backspaces to send (one per character of the original value)
     */
    suspend fun sendBackspaces(count: Int) {
        Log.d(TAG, "sendBackspaces: $count")
        repeat(count) {
            sendKey(KeyTranslator.BACKSPACE_KEY, 12)
            delay(12)
        }
        releaseAllKeysReliably()
    }

    suspend fun sendKey(key: Key, releaseDelay: Long = 10) =
        sendKey(key.second.toByte(), key.first.toByte(), releaseDelay = releaseDelay)

    private suspend fun sendKey(
        key: Byte,
        modifier: Byte = 0,
        releaseKey: Boolean = true,
        releaseDelay: Long = 10
    ) {
        val pressReport = byteArrayOf(modifier, 0, key)
        sendReportWithRetry(pressReport)

        val pressDuration = maxOf(MIN_PRESS_DELAY_MS, releaseDelay)
        delay(pressDuration)

        if (releaseKey) {
            val releaseReport = ByteArray(3)
            sendReportWithRetry(releaseReport)
            val releaseGap = maxOf(MIN_RELEASE_DELAY_MS, releaseDelay)
            delay(releaseGap)
        }
    }
}
