package com.walkp.cadence

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.walkp.cadence.dsp.CadenceEstimator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * 步频估计引擎。
 *
 * 职责边界：
 *  · 采样、缓冲、门控、平滑、60 秒滚动统计、每秒输出 —— 在这里
 *  · 纯信号处理（重采样 / FFT / 主峰 / 峰值检测）—— 在 CadenceEstimator，可离线单测
 *
 * 手持与口袋的差异由 [PlacementConfigs] 提供参数，运行时可通过 [setPlacement] 热切换。
 */
class CadenceEngine(context: Context) {

    enum class WalkState { IDLE, UNSTABLE, WALKING }

    data class Snapshot(
        val instantSpm: Float? = null,
        val avg60Spm: Float? = null,
        val state: WalkState = WalkState.IDLE,
        val confidence: Float = 0f,
        val spectralSpm: Float? = null,
        val peakSpm: Float? = null,
        val history: List<Float?> = emptyList()
    )

    // ---------- 与位置无关的固定参数 ----------
    private object Cfg {
        const val SAMPLE_PERIOD_US = 20_000              // 目标 50 Hz
        const val ANALYSIS_PERIOD_NS = 250_000_000L      // 每 250 ms 分析一次
        const val WINDOW_SEC = 4.0
        const val FFT_SIZE = 256
        const val HOLD_MS = 1_500L
        const val MIN_AVG_SLOTS = 15
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // ---------- 位置相关参数，可热切换 ----------
    private var placement: Placement = Placement.HAND
    private var config: EngineConfig = PlacementConfigs.forPlacement(Placement.HAND)
    private var estimator = buildEstimator(config)

    private fun buildEstimator(c: EngineConfig) = CadenceEstimator(
        windowSec = Cfg.WINDOW_SEC,
        fftSize = Cfg.FFT_SIZE,
        harmonicCheckHz = c.harmonicCheckHz,
        harmonicSubRatio = c.harmonicSubRatio
    )

    fun setPlacement(p: Placement) {
        if (p == placement) return
        placement = p
        config = PlacementConfigs.forPlacement(p)
        estimator = buildEstimator(config)
        smoothed = null          // 切换瞬间重置平滑，避免旧位置的读数拖尾
    }

    fun currentPlacement(): Placement = placement

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // ---------- 原始样本环形缓冲（时间戳 + 模值） ----------
    private val cap = 1024
    private val tBuf = LongArray(cap)
    private val vBuf = DoubleArray(cap)
    private var writePos = 0
    private var buffered = 0

    private val wT = DoubleArray(cap)
    private val wV = DoubleArray(cap)

    // ---------- 60 秒环形槽 ----------
    private val slotCount = 60
    private val slots = arrayOfNulls<Float>(slotCount)
    private var slotIdx = 0
    private var slotStartNs = 0L
    private var slotSum = 0.0
    private var slotSamples = 0

    private var lastAnalysisNs = 0L
    private var lastWalkingNs = Long.MIN_VALUE
    private var smoothed: Float? = null

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /**
     * 每秒一个样本（该秒瞬时值的平均，null 表示该秒无效）。
     * 给后台会话记录用；无订阅者时 tryEmit 直接丢弃，零成本。
     */
    private val _secondFlow = MutableSharedFlow<Float?>(extraBufferCapacity = 64)
    val secondFlow: SharedFlow<Float?> = _secondFlow.asSharedFlow()

    val sensorAvailable: Boolean get() = accelerometer != null
    val isRunning: Boolean get() = thread != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val t = event.timestamp
            val x = event.values[0].toDouble()
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            // 先取模值：姿态无关；重力占主导，不会被整流成 2 倍频
            val mag = sqrt(x * x + y * y + z * z)
            tBuf[writePos] = t
            vBuf[writePos] = mag
            writePos = (writePos + 1) % cap
            if (buffered < cap) buffered++

            if (t - lastAnalysisNs >= Cfg.ANALYSIS_PERIOD_NS) {
                lastAnalysisNs = t
                analyze(t)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (accelerometer == null || isRunning) return
        val ht = HandlerThread("cadence-sensor").apply { start() }
        thread = ht
        handler = Handler(ht.looper)
        reset()
        sensorManager.registerListener(listener, accelerometer, Cfg.SAMPLE_PERIOD_US, handler)
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(listener)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private fun reset() {
        writePos = 0
        buffered = 0
        slotIdx = 0
        slotStartNs = 0L
        slotSum = 0.0
        slotSamples = 0
        lastAnalysisNs = 0L
        lastWalkingNs = Long.MIN_VALUE
        smoothed = null
        for (i in 0 until slotCount) slots[i] = null
        _snapshot.value = Snapshot()
    }

    private fun collect(sinceNs: Long): Int {
        val oldest = if (buffered < cap) 0 else writePos
        var n = 0
        for (i in 0 until buffered) {
            val idx = (oldest + i) % cap
            if (tBuf[idx] >= sinceNs) {
                wT[n] = (tBuf[idx] - sinceNs) / 1e9
                wV[n] = vBuf[idx]
                n++
            }
        }
        return n
    }

    private fun analyze(nowNs: Long) {
        if (slotStartNs == 0L) slotStartNs = nowNs

        val spanNs = (Cfg.WINDOW_SEC * 1e9).toLong()
        val n = collect(nowNs - spanNs)
        val r = if (n >= 32) estimator.estimate(wT, wV, n) else null

        val c = config
        val spectralSpm = r?.spectralSpm
        val peakSpm = r?.peakSpm
        val energy = r?.rms ?: 0.0
        val prominence = r?.prominence ?: 0f
        val cv = r?.cv ?: 1f
        val peakCount = r?.peakCount ?: 0

        // 门控：能量量级 + 主峰突出度 + 节律性，三者同时满足才算在走路
        val energyOk = energy >= c.minRms && energy <= c.maxRms
        val prominenceOk = prominence >= c.minProminence
        val rhythmOk = peakCount < 4 || cv <= c.maxCv
        val gateOk = energyOk && prominenceOk && rhythmOk
        val walking = gateOk && spectralSpm != null

        var confidence = clamp01((prominence - 0.25f) / 0.35f)
        if (peakCount >= 4) confidence *= clamp01((0.30f - cv) / 0.15f)

        // 融合：频谱为主（稳），峰值为辅（快）；两者分歧时降置信度
        val instant: Float? = if (gateOk && spectralSpm != null) {
            if (peakSpm != null && peakSpm > 0f &&
                CadenceEstimator.relativeDiff(spectralSpm, peakSpm) < 0.10f
            ) {
                0.7f * spectralSpm + 0.3f * peakSpm
            } else {
                confidence *= if (peakSpm == null) 0.6f else 0.5f
                spectralSpm
            }
        } else null

        val smoothedInstant = if (instant != null) {
            val prev = smoothed
            smoothed = if (prev == null) instant else prev + c.smoothAlpha * (instant - prev)
            smoothed
        } else {
            smoothed = null
            null
        }

        commitSeconds(nowNs, walking, smoothedInstant)
        publish(nowNs, smoothedInstant, spectralSpm, peakSpm, walking, confidence)
    }

    /** 把当前这一秒的分析结果累加进 60 槽环形缓冲，提交时向 secondFlow 广播。 */
    private fun commitSeconds(nowNs: Long, walking: Boolean, instant: Float?) {
        if (walking && instant != null) {
            slotSum += instant
            slotSamples++
        }
        while (nowNs - slotStartNs >= 1_000_000_000L) {
            val value = if (slotSamples > 0) (slotSum / slotSamples).toFloat() else null
            slots[slotIdx] = value
            _secondFlow.tryEmit(value)
            slotIdx = (slotIdx + 1) % slotCount
            slotStartNs += 1_000_000_000L
            slotSum = 0.0
            slotSamples = 0
        }
    }

    private fun publish(
        nowNs: Long,
        instant: Float?,
        spectral: Float?,
        peak: Float?,
        walking: Boolean,
        confidence: Float
    ) {
        if (walking) lastWalkingNs = nowNs
        val holding = instant == null && (nowNs - lastWalkingNs) / 1_000_000L < Cfg.HOLD_MS

        val state = when {
            instant != null -> WalkState.WALKING
            holding -> WalkState.UNSTABLE
            else -> WalkState.IDLE
        }

        var valid = 0
        var sum = 0.0
        for (v in slots) {
            if (v != null) {
                valid++
                sum += v
            }
        }
        val avg = if (valid >= Cfg.MIN_AVG_SLOTS) (sum / valid).toFloat() else null

        val history = ArrayList<Float?>(slotCount)
        for (i in 0 until slotCount) history.add(slots[(slotIdx + i) % slotCount])

        _snapshot.value = Snapshot(
            instantSpm = instant,
            avg60Spm = avg,
            state = state,
            confidence = confidence.coerceIn(0f, 1f),
            spectralSpm = spectral,
            peakSpm = peak,
            history = history
        )
    }

    private fun clamp01(v: Float): Float = when {
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }
}
