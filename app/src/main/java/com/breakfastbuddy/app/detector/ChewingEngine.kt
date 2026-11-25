package com.breakfastbuddy.app.detector

import kotlin.math.abs
import kotlin.math.min

/**
 * 單一影格的臉部與手部特徵資料
 */
data class FrameObservation(
    val timestampMs: Long,
    val faceDetected: Boolean,
    val mouthOpenRatio: Float,       // 嘴唇開合高度 / 臉部高度
    val jawRelativeY: Float,         // 下巴相對鼻樑高度 / 臉部高度 (偵測閉嘴咀嚼的下顎起伏)
    val handNearMouth: Boolean,      // 手腕/食指是否進入嘴部區域
    val mouthVisualChange: Float     // 嘴部區域亮度/紋理變化量 (偵測湯匙/食物靠近遮擋)
)

/**
 * 家長可調整的參數設定
 */
data class MonitoringConfig(
    val stopChewTimeoutSec: Int = 6,          // 停止咀嚼幾秒後暫停影片
    val basePenaltySec: Int = 5,              // 第一次忘記咀嚼的強制暫停秒數
    val maxPenaltySec: Int = 60,              // 延長暫停的上限秒數
    val biteValiditySec: Int = 30,            // 每一口新食物允許的最大咀嚼秒數 (防止一直空咬)
    val maxChewsPerBite: Int = 35,            // 每一口新食物允許的最大咀嚼次數 (防止一直空咬)
    val requireNewBiteToPreventFake: Boolean = true, // 是否啟用「防假裝空咬(需偵測新食物進入口內)」
    val voiceReminderEnabled: Boolean = true, // 是否開啟中文語音溫馨提醒
    val resumeChewCyclesRequired: Int = 2     // 恢復播放前需要連續偵測到的有效咀嚼次數
)

enum class MonitoringPhase {
    IDLE,                     // 尚未開始監測
    WAITING_FOR_FIRST_BITE,   // 等待第一口食物進入口內
    EATING_PLAYING,           // 正常進食咀嚼中 -> 播放 YouTube
    PAUSED_STOPPED_CHEWING,   // 停止咀嚼 -> 暫停影片並倒數懲罰時間
    PAUSED_FAKE_CHEWING,      // 偵測到疑似空咬 (未見新食物入口) -> 暫停並要求吃新的一口
    PAUSED_NO_FACE            // 鏡頭找不到小朋友 -> 暫停影片
}

data class MonitoringUiState(
    val phase: MonitoringPhase = MonitoringPhase.IDLE,
    val isMonitoring: Boolean = false,
    val shouldPlayVideo: Boolean = true,
    val faceDetected: Boolean = false,
    val isChewingNow: Boolean = false,
    val handNearMouth: Boolean = false,
    val verifiedBiteCount: Int = 0,
    val chewsSinceLastBite: Int = 0,
    val consecutiveForgetCount: Int = 0,
    val currentPenaltyTotalSec: Int = 0,
    val penaltyRemainingSec: Int = 0,
    val statusTitle: String = "準備開始吃早餐囉！",
    val statusDetail: String = "按下「開始監測」後，就會自動追蹤吃早餐進度",
    val reminderMessage: String? = null,
    val speechEventId: Long = 0L,
    val speechMessage: String? = null
)

/**
 * 早餐咀嚼與防假裝進食核心狀態機：
 * 1. 利用嘴唇開合與下顎起伏的週期性波峰/波谷偵測「咀嚼」
 * 2. 利用「手靠近嘴巴 + 張口動作」或「嘴部遮擋變化 + 張口動作」偵測「新食物進入口內」
 * 3. 若超出每口食物的合理咀嚼配額卻未進食新食物，判定為疑似假裝空咬
 * 4. 每次忘記咀嚼，強制暫停的冷卻時間遞增 (5s -> 10s -> 15s -> 25s -> 40s -> 60s)
 * 5. 必須等到冷卻結束 + 真正恢復有效咀嚼後，才恢復播放 YouTube
 */
class ChewingEngine {

    private data class SignalPoint(val timeMs: Long, val value: Float)

    private val jawHistory = ArrayDeque<SignalPoint>()
    private var lastChewEventMs: Long = 0L
    private var lastVerifiedBiteMs: Long = 0L
    private var lastFaceSeenMs: Long = 0L

    // 用於偵測「送食入口」的短暫狀態
    private var approachDetectedMs: Long = 0L
    private var wideOpenDuringApproach: Boolean = false

    // 用於偵測咀嚼波峰/波谷
    private var smoothedSignal: Float = 0f
    private var lastPeakTimeMs: Long = 0L
    private var wasRising: Boolean = false
    private var localMin: Float = Float.MAX_VALUE
    private var localMax: Float = Float.MIN_VALUE

    // 恢復播放計數與獎勵重置計時
    private var resumeChewCount: Int = 0
    private var needNewBiteBeforeResume: Boolean = false
    private var penaltyEndTimeMs: Long = 0L
    private var goodEatingStartMs: Long = 0L

    private var state: MonitoringUiState = MonitoringUiState()

    fun getCurrentState(): MonitoringUiState = state

    fun startMonitoring(nowMs: Long, config: MonitoringConfig): MonitoringUiState {
        resetInternalBuffers(nowMs)
        val initialPhase = if (config.requireNewBiteToPreventFake) {
            MonitoringPhase.WAITING_FOR_FIRST_BITE
        } else {
            MonitoringPhase.EATING_PLAYING
        }
        needNewBiteBeforeResume = config.requireNewBiteToPreventFake
        state = MonitoringUiState(
            phase = initialPhase,
            isMonitoring = true,
            shouldPlayVideo = !config.requireNewBiteToPreventFake,
            statusTitle = if (config.requireNewBiteToPreventFake) "請吃第一口早餐喔 🥪" else "監測中：開心吃早餐",
            statusDetail = if (config.requireNewBiteToPreventFake) {
                "偵測到食物送進嘴巴並開始咀嚼後，影片就會自動播放！"
            } else {
                "持續咀嚼中，影片正常播放"
            },
            reminderMessage = if (config.requireNewBiteToPreventFake) "吃一口早餐並開始咬咬，影片就會開始播放喔！" else null
        )
        return state
    }

    fun stopMonitoring(): MonitoringUiState {
        state = MonitoringUiState(
            phase = MonitoringPhase.IDLE,
            isMonitoring = false,
            shouldPlayVideo = true,
            verifiedBiteCount = state.verifiedBiteCount,
            statusTitle = "監測已暫停",
            statusDetail = "點擊「開始監測」重新啟動吃早餐小幫手"
        )
        return state
    }

    /**
     * 家長手動確認「真的有吃一口」（例如使用不遮擋的吸管或特殊餐具時的輔助鈕）
     */
    fun markManualBite(nowMs: Long): MonitoringUiState {
        if (!state.isMonitoring) return state
        registerVerifiedBite(nowMs)
        return state
    }

    /**
     * 重置懲罰階數
     */
    fun resetPenaltyCounter(): MonitoringUiState {
        state = state.copy(consecutiveForgetCount = 0, currentPenaltyTotalSec = 0)
        return state
    }

    fun processFrame(obs: FrameObservation, config: MonitoringConfig): MonitoringUiState {
        if (!state.isMonitoring) return state

        val now = obs.timestampMs

        // 1. 檢查是否偵測到臉部
        if (!obs.faceDetected) {
            val missingSec = (now - lastFaceSeenMs) / 1000L
            if (lastFaceSeenMs > 0L && missingSec >= 4L) {
                state = state.copy(
                    phase = MonitoringPhase.PAUSED_NO_FACE,
                    shouldPlayVideo = false,
                    faceDetected = false,
                    isChewingNow = false,
                    handNearMouth = false,
                    statusTitle = "找不到小朋友喔 👀",
                    statusDetail = "請坐回平板前面，讓鏡頭看到臉部",
                    reminderMessage = "請坐好面向平板，邊看邊吃早餐喔！"
                )
            } else {
                state = state.copy(faceDetected = false)
            }
            return state
        }
        lastFaceSeenMs = now

        // 2. 偵測「新食物進入口內」(防假裝空咬核心)
        val biteDetectedNow = detectNewFoodIntake(obs)
        if (biteDetectedNow) {
            registerVerifiedBite(now)
        }

        // 3. 偵測「咀嚼週期」(結合嘴唇開合與下顎起伏)
        val chewCycleDetected = detectChewingCycle(obs)
        if (chewCycleDetected) {
            lastChewEventMs = now
            state = state.copy(chewsSinceLastBite = state.chewsSinceLastBite + 1)

            if (state.phase == MonitoringPhase.PAUSED_STOPPED_CHEWING ||
                state.phase == MonitoringPhase.PAUSED_FAKE_CHEWING ||
                state.phase == MonitoringPhase.WAITING_FOR_FIRST_BITE
            ) {
                val penaltyDone = now >= penaltyEndTimeMs
                if (penaltyDone && !needNewBiteBeforeResume) {
                    resumeChewCount++
                }
            }
        }

        val isRecentlyChewing = (now - lastChewEventMs) <= 2200L

        // 4. 依目前階段執行狀態轉換
        when (state.phase) {
            MonitoringPhase.WAITING_FOR_FIRST_BITE,
            MonitoringPhase.PAUSED_NO_FACE -> {
                if (state.phase == MonitoringPhase.PAUSED_NO_FACE && !needNewBiteBeforeResume && isRecentlyChewing) {
                    enterPlayingState(now)
                } else if (!needNewBiteBeforeResume && resumeChewCount >= config.resumeChewCyclesRequired) {
                    enterPlayingState(now)
                } else {
                    val prompt = if (needNewBiteBeforeResume) {
                        "請先吃一口早餐進嘴巴喔 🥄"
                    } else {
                        "有吃到食物囉！繼續咬咬 (${resumeChewCount}/${config.resumeChewCyclesRequired})"
                    }
                    state = state.copy(
                        faceDetected = true,
                        isChewingNow = isRecentlyChewing,
                        handNearMouth = obs.handNearMouth,
                        shouldPlayVideo = false,
                        statusTitle = if (needNewBiteBeforeResume) "等待吃下一口早餐" else "正在確認咀嚼中...",
                        statusDetail = prompt,
                        reminderMessage = prompt
                    )
                }
            }

            MonitoringPhase.EATING_PLAYING -> {
                val secSinceLastChew = (now - lastChewEventMs) / 1000L
                val secSinceLastBite = if (lastVerifiedBiteMs > 0L) (now - lastVerifiedBiteMs) / 1000L else 0L

                // 獎勵機制：連續認真進食超過 60 秒，自動減輕一次忘記紀錄
                if (goodEatingStartMs > 0L && (now - goodEatingStartMs) >= 60_000L && state.consecutiveForgetCount > 0) {
                    goodEatingStartMs = now
                    state = state.copy(consecutiveForgetCount = (state.consecutiveForgetCount - 1).coerceAtLeast(0))
                }

                // 檢查 A：是否疑似假裝空咬 (嘴巴一直動但太久沒吃進新食物)
                val exceededBiteBudget = config.requireNewBiteToPreventFake &&
                    (secSinceLastBite >= config.biteValiditySec || state.chewsSinceLastBite >= config.maxChewsPerBite)

                if (exceededBiteBudget) {
                    triggerPausePenalty(
                        now = now,
                        config = config,
                        phase = MonitoringPhase.PAUSED_FAKE_CHEWING,
                        requireBite = true,
                        title = "要再吃一口新的食物喔！ 🥪",
                        reminder = "不可以只動嘴巴喔！請再吃一口新的早餐並咀嚼",
                        speech = "不可以只動嘴巴喔，請再吃一口新的早餐"
                    )
                } else if (secSinceLastChew >= config.stopChewTimeoutSec) {
                    // 檢查 B：是否停止咀嚼超過門檻
                    triggerPausePenalty(
                        now = now,
                        config = config,
                        phase = MonitoringPhase.PAUSED_STOPPED_CHEWING,
                        requireBite = false,
                        title = "嘴巴停下來囉！ ⏸️",
                        reminder = "忘記咬咬囉！請繼續咀嚼早餐，倒數結束就會繼續播放影片",
                        speech = "寶貝忘記咬咬囉，快點繼續咀嚼早餐喔"
                    )
                } else {
                    state = state.copy(
                        faceDetected = true,
                        isChewingNow = isRecentlyChewing,
                        handNearMouth = obs.handNearMouth,
                        shouldPlayVideo = true,
                        statusTitle = if (isRecentlyChewing) "太棒了！認真咀嚼中 ⭐" else "記得繼續咬咬喔...",
                        statusDetail = "已吃 ${state.verifiedBiteCount} 口｜距上次咀嚼 ${secSinceLastChew} 秒",
                        reminderMessage = null
                    )
                }
            }

            MonitoringPhase.PAUSED_STOPPED_CHEWING,
            MonitoringPhase.PAUSED_FAKE_CHEWING -> {
                val remainingMs = (penaltyEndTimeMs - now).coerceAtLeast(0L)
                val remainingSec = ((remainingMs + 999L) / 1000L).toInt()

                if (remainingMs == 0L && !needNewBiteBeforeResume && resumeChewCount >= config.resumeChewCyclesRequired) {
                    enterPlayingState(now)
                } else {
                    val detail = when {
                        remainingSec > 0 && needNewBiteBeforeResume ->
                            "暫停倒數 ${remainingSec} 秒，且需要吃一口新食物進嘴巴喔！"
                        remainingSec > 0 ->
                            "暫停延長中：還需等待 ${remainingSec} 秒，並保持咀嚼"
                        needNewBiteBeforeResume ->
                            "倒數結束！請吃一口新食物進嘴巴並咀嚼 🥄"
                        else ->
                            "倒數結束！請開始咀嚼 (${resumeChewCount}/${config.resumeChewCyclesRequired}) 即可播放"
                    }
                    state = state.copy(
                        faceDetected = true,
                        isChewingNow = isRecentlyChewing,
                        handNearMouth = obs.handNearMouth,
                        shouldPlayVideo = false,
                        penaltyRemainingSec = remainingSec,
                        statusDetail = detail,
                        reminderMessage = detail
                    )
                }
            }

            MonitoringPhase.IDLE -> Unit
        }

        return state
    }

    /**
     * 偵測新食物進入口內：
     * 條件：偵測到「手部靠近嘴部」或「嘴部明顯視覺遮擋變化(湯匙/食物)」後 2.2 秒內，
     * 伴隨一次比一般咀嚼更大的「張口進食動作 (mouthOpenRatio >= 0.085f)」，
     * 且距離上次進食至少間隔 3 秒 (避免單一口重複計算)。
     */
    private fun detectNewFoodIntake(obs: FrameObservation): Boolean {
        val now = obs.timestampMs
        if (now - lastVerifiedBiteMs < 3000L) return false

        val approachSignal = obs.handNearMouth || obs.mouthVisualChange >= 18f
        if (approachSignal) {
            approachDetectedMs = now
        }

        val withinApproachWindow = (now - approachDetectedMs) <= 2200L
        if (withinApproachWindow && obs.mouthOpenRatio >= 0.085f) {
            wideOpenDuringApproach = true
        }

        // 當張口送食完成並開始合嘴 (mouthOpenRatio 回落) 時，確認完成一次新食物入口
        if (wideOpenDuringApproach && obs.mouthOpenRatio < 0.07f && (now - approachDetectedMs) <= 3000L) {
            wideOpenDuringApproach = false
            approachDetectedMs = 0L
            return true
        }

        // 逾時清除暫存
        if ((now - approachDetectedMs) > 3200L) {
            wideOpenDuringApproach = false
        }
        return false
    }

    private fun registerVerifiedBite(nowMs: Long) {
        lastVerifiedBiteMs = nowMs
        lastChewEventMs = nowMs
        needNewBiteBeforeResume = false
        state = state.copy(
            verifiedBiteCount = state.verifiedBiteCount + 1,
            chewsSinceLastBite = 0
        )
    }

    /**
     * 利用綜合訊號 (嘴唇開合 + 下顎垂直位置) 的波峰與波谷來偵測真實咀嚼節奏。
     * 人類咀嚼頻率約 0.5Hz ~ 2.8Hz (每下間隔約 350ms ~ 2000ms)。
     */
    private fun detectChewingCycle(obs: FrameObservation): Boolean {
        val now = obs.timestampMs
        val combinedSignal = (obs.mouthOpenRatio * 0.65f) + (obs.jawRelativeY * 0.35f)

        // 低通平滑濾波減少雜訊
        smoothedSignal = if (jawHistory.isEmpty()) {
            combinedSignal
        } else {
            smoothedSignal * 0.55f + combinedSignal * 0.45f
        }

        jawHistory.addLast(SignalPoint(now, smoothedSignal))
        while (jawHistory.isNotEmpty() && (now - jawHistory.first().timeMs) > 2500L) {
            jawHistory.removeFirst()
        }

        if (jawHistory.size < 4) return false

        val prev = jawHistory[jawHistory.size - 2].value
        val curr = smoothedSignal
        val delta = curr - prev

        if (curr < localMin) localMin = curr
        if (curr > localMax) localMax = curr

        var chewDetected = false
        val minAmplitude = 0.016f // 過濾微幅抖動，需達有效下顎/嘴部起伏幅度

        if (delta > 0.002f) {
            wasRising = true
        } else if (delta < -0.002f && wasRising) {
            // 剛越過一個波峰
            val amplitude = localMax - localMin
            val intervalMs = now - lastPeakTimeMs
            if (amplitude >= minAmplitude && intervalMs in 320L..2500L) {
                chewDetected = true
                lastPeakTimeMs = now
            } else if (lastPeakTimeMs == 0L && amplitude >= minAmplitude) {
                lastPeakTimeMs = now
                chewDetected = true
            }
            wasRising = false
            localMin = curr
            localMax = curr
        }

        return chewDetected
    }

    private fun triggerPausePenalty(
        now: Long,
        config: MonitoringConfig,
        phase: MonitoringPhase,
        requireBite: Boolean,
        title: String,
        reminder: String,
        speech: String
    ) {
        val nextForgetCount = state.consecutiveForgetCount + 1
        // 漸進式延長暫停時間：base * (1, 2, 3, 5, 7, 10...) 並受 maxPenaltySec 上限保護
        val multiplier = when (nextForgetCount) {
            1 -> 1
            2 -> 2
            3 -> 3
            4 -> 5
            5 -> 7
            else -> 10
        }
        val penaltySec = min(config.basePenaltySec * multiplier, config.maxPenaltySec)

        penaltyEndTimeMs = now + penaltySec * 1000L
        resumeChewCount = 0
        goodEatingStartMs = 0L
        needNewBiteBeforeResume = requireBite

        state = state.copy(
            phase = phase,
            shouldPlayVideo = false,
            consecutiveForgetCount = nextForgetCount,
            currentPenaltyTotalSec = penaltySec,
            penaltyRemainingSec = penaltySec,
            statusTitle = title,
            statusDetail = reminder,
            reminderMessage = reminder,
            speechEventId = if (config.voiceReminderEnabled) now else state.speechEventId,
            speechMessage = if (config.voiceReminderEnabled) speech else null
        )
    }

    private fun enterPlayingState(now: Long) {
        lastChewEventMs = now
        if (lastVerifiedBiteMs == 0L) lastVerifiedBiteMs = now
        goodEatingStartMs = now
        resumeChewCount = 0
        state = state.copy(
            phase = MonitoringPhase.EATING_PLAYING,
            shouldPlayVideo = true,
            penaltyRemainingSec = 0,
            statusTitle = "太棒了！繼續看影片配早餐 ⭐",
            statusDetail = "已吃 ${state.verifiedBiteCount} 口｜持續咀嚼中",
            reminderMessage = null
        )
    }

    private fun resetInternalBuffers(nowMs: Long) {
        jawHistory.clear()
        lastChewEventMs = nowMs
        lastVerifiedBiteMs = 0L
        lastFaceSeenMs = nowMs
        approachDetectedMs = 0L
        wideOpenDuringApproach = false
        smoothedSignal = 0f
        lastPeakTimeMs = 0L
        wasRising = false
        localMin = Float.MAX_VALUE
        localMax = Float.MIN_VALUE
        resumeChewCount = 0
        penaltyEndTimeMs = 0L
        goodEatingStartMs = nowMs
    }
}
