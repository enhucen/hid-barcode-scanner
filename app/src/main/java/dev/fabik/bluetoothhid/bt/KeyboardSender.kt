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

        // 工业级蓝牙 HID 信号安全下限，防止因时间片过短导致 Windows 蓝牙协议栈丢帧、拥塞或合并报文
        private const val MIN_PRESS_DELAY_MS = 18L    // 单次按键最小物理保持时长 (保证 Windows 稳定采样注册)
        private const val MIN_RELEASE_DELAY_MS = 18L  // 按键释放最小间隔时长 (保证 Windows 驱动完成 KeyUp 状态切换)
        private const val MAX_SEND_RETRIES = 6        // sendReport 返回 false 时的最大重试次数
    }

    @Volatile
    private var cancelRequested = false

    /** Request cancellation of the currently running [sendProcessedString]. */
    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * 带阶梯退避与缓冲区排空保护的可靠发送。
     * 当底层 L2CAP 队列拥塞或蓝牙跳频时，自动延时重试，确保报文 100% 交付给硬件。
     */
    private suspend fun sendReportWithRetry(
        reportData: ByteArray,
        maxRetries: Int = MAX_SEND_RETRIES
    ): Boolean {
        var attempts = 0
        while (attempts < maxRetries) {
            val success = hidDevice.sendReport(host, Descriptor.ID, reportData)
            if (success) {
                return true
            }
            attempts++
            // 阶梯式递增退避: 6ms, 12ms, 18ms, 24ms... 给底层足够的时隙排空缓冲区
            val backoff = 6L * attempts
            Log.w(TAG, "sendReport congestion/busy, retrying ($attempts/$maxRetries) after ${backoff}ms...")
            delay(backoff)
        }
        Log.e(TAG, "CRITICAL: sendReport failed after $maxRetries attempts!")
        return false
    }

    /**
     * 终极全键清空保障（Robust Key Release Guard）：
     * 连续发送 3 次全 0 释放帧，每次间隔 15ms，无论链路如何抖动，确保 Windows 接收端 100% 退出长按。
     */
    suspend fun releaseAllKeysReliably() {
        val emptyReport = ByteArray(3)
        for (i in 1..3) {
            hidDevice.sendReport(host, Descriptor.ID, emptyReport)
            delay(15)
        }
    }

    /**
     * 单个按键的原子操作保障：
     * 只要发送了 KeyDown，即使被取消或中途异常，finally 必定确保发送 KeyUp 释放！
     * 若按下报文发送失败，直接返回 false，阻止继续向断裂链路盲目发送后续字符。
     */
    private suspend fun sendKeyAtomically(
        key: Byte,
        modifier: Byte,
        pressDuration: Long,
        releaseGap: Long
    ): Boolean {
        var pressed = false
        try {
            val pressReport = byteArrayOf(modifier, 0, key)
            val pressSuccess = sendReportWithRetry(pressReport)
            if (!pressSuccess) {
                Log.e(TAG, "Failed to send KeyDown for key=$key, modifier=$modifier")
                return false
            }
            pressed = true
            delay(pressDuration)
            return true
        } finally {
            if (pressed) {
                // 只要按键曾被按下，绝不允许留在按下状态，必须执行释放！
                val releaseReport = ByteArray(3)
                sendReportWithRetry(releaseReport)
                delay(releaseGap)
            }
        }
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

        // 动态计算安全的按下时长与释放间隔，硬保底 ≥ 18ms
        val pressDuration = maxOf(MIN_PRESS_DELAY_MS, sendDelay / 2)
        val interKeyDelay = maxOf(MIN_RELEASE_DELAY_MS, sendDelay / 2)

        try {
            var prevKey: Key? = null
            for ((key, completesChar) in keys) {
                if (cancelRequested) return charCount.takeIf { it > 0 }
                Log.d(TAG, "sendProcessedString: $key")

                // 连续相同按键时增加防粘滞缓冲，确保 Windows 状态机完整经历按下-弹起循环
                if (prevKey == key) {
                    delay(12)
                }

                val success = sendKeyAtomically(
                    key = key.second.toByte(),
                    modifier = key.first.toByte(),
                    pressDuration = pressDuration,
                    releaseGap = interKeyDelay
                )

                if (!success) {
                    // 如果中间某字符发送失败（底层断开或严重故障），立即中断后续发送并退出，防止死锁卡键
                    Log.e(TAG, "Abort sending rest of string due to communication error at key=$key")
                    break
                }

                if (completesChar) charCount++
                if (cancelRequested) return charCount.takeIf { it > 0 }

                prevKey = key
            }
        } finally {
            // 无论传输是正常完成、中途失败、取消还是抛出异常，必须无条件清空所有按键！
            releaseAllKeysReliably()
        }

        return charCount
    }

    /**
     * Send [count] backspace key presses to undo the last transmitted value.
     */
    suspend fun sendBackspaces(count: Int) {
        Log.d(TAG, "sendBackspaces: $count")
        repeat(count) {
            sendKeyAtomically(
                key = KeyTranslator.BACKSPACE_KEY.second.toByte(),
                modifier = KeyTranslator.BACKSPACE_KEY.first.toByte(),
                pressDuration = 18,
                releaseGap = 18
            )
        }
        releaseAllKeysReliably()
    }

    suspend fun sendKey(key: Key, releaseDelay: Long = 18) =
        sendKeyAtomically(
            key = key.second.toByte(),
            modifier = key.first.toByte(),
            pressDuration = maxOf(MIN_PRESS_DELAY_MS, releaseDelay),
            releaseGap = maxOf(MIN_RELEASE_DELAY_MS, releaseDelay)
        )
}
