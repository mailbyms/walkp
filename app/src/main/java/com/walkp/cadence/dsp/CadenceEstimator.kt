package com.walkp.cadence.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 步频估计核心：从“时间戳不均匀的加速度模值序列”算出步频。
 *
 * 这里刻意不依赖任何 Android API，好处有两个：
 *  1. 可以在纯 JVM 单元测试里用合成信号验证精度（见 CadenceEstimatorTest）；
 *  2. 可以把真机导出的 CSV 拉到电脑上离线回放调参。
 *
 * 输入必须是【三轴模值】sqrt(x²+y²+z²)，且【未】去除重力 —— 重力占主导时
 * 模值 ≈ g + a_v，天然保留步频基频；若先把重力从各轴减掉再取模，信号会被
 * 整流成 2 倍频，结果直接错一倍。
 */
class CadenceEstimator(
    private val windowSec: Double = 4.0,
    private val fftSize: Int = 256,
    private val minHz: Double = 1.0,        // 60 spm
    private val maxHz: Double = 3.0,        // 180 spm
    private val peakMinIntervalSec: Double = 0.25,
    private val harmonicCheckHz: Double = 2.4,
    private val harmonicSubRatio: Double = 0.50
) {

    data class Result(
        /** 频谱主峰换算的步频（步/分钟），null 表示数据不足 */
        val spectralSpm: Float?,
        /** 时域峰间隔中位数换算的步频，null 表示峰太少 */
        val peakSpm: Float?,
        /** 去趋势后的 RMS（m/s²），用于能量门控 */
        val rms: Double,
        /** 主峰功率 / 1~3 Hz 总功率，0~1 */
        val prominence: Float,
        /** 峰间隔变异系数，越小越规律 */
        val cv: Float,
        val peakCount: Int
    )

    private val uni = DoubleArray(fftSize)
    private val spectrum = DoubleArray(fftSize / 2 + 1)
    private val hann = SignalUtil.hann(fftSize)
    private val intervals = ArrayList<Double>(32)

    /**
     * @param t 单调递增的时间戳，单位秒（零点任意）
     * @param v 与 t 对应的加速度模值
     * @param count 有效元素个数
     */
    fun estimate(t: DoubleArray, v: DoubleArray, count: Int): Result {
        if (count < 32) return Result(null, null, 0.0, 0f, 1f, 0)

        val span = t[count - 1] - t[0]
        if (span < windowSec * 0.9) return Result(null, null, 0.0, 0f, 1f, 0)

        // 1) 等间隔重采样：Android 传感器实际采样间隔不均匀，
        //    不重采样会让频率估计产生系统性偏差。
        if (SignalUtil.resampleUniform(t, v, count, uni, fftSize) < 0) {
            return Result(null, null, 0.0, 0f, 1f, 0)
        }
        val fsEff = (fftSize - 1) / span          // 重采样后的等效采样率
        val binHz = fsEff / fftSize

        // 2) 去趋势（去直流与线性漂移）
        SignalUtil.detrend(uni)

        // 3) 能量（加窗前计算）
        val energy = SignalUtil.rms(uni)

        // 4) 时域峰值检测
        val peaks = SignalUtil.detectPeaks(uni, fftSize, fsEff, peakMinIntervalSec)
        intervals.clear()
        for (i in 1 until peaks.size) intervals.add(peaks[i] - peaks[i - 1])
        val peakSpm = medianIntervalToSpm(intervals)

        // 5) 加汉宁窗 + FFT（magnitudeSpectrum 内部会拷贝，不破坏 uni）
        for (i in 0 until fftSize) uni[i] *= hann[i]
        RealFft.magnitudeSpectrum(uni, spectrum)

        // 6) 限定在 1~3 Hz 内找主峰
        val kMin = ceil(minHz / binHz).toInt().coerceAtLeast(1)
        val kMax = floor(maxHz / binHz).toInt().coerceAtMost(spectrum.size - 2)
        if (kMax <= kMin) return Result(null, peakSpm, energy, 0f, cvOf(intervals), peaks.size)

        var bestK = kMin
        var bestPow = -1.0
        var totalPow = 0.0
        for (k in kMin..kMax) {
            val p = spectrum[k] * spectrum[k]
            totalPow += p
            if (p > bestPow) {
                bestPow = p
                bestK = k
            }
        }
        if (totalPow <= 0.0) return Result(null, peakSpm, energy, 0f, cvOf(intervals), peaks.size)
        val prominence = (bestPow / totalPow).toFloat()

        // 7) 抛物线插值细化主峰。
        //    4 秒窗的分辨率只有 0.25 Hz = 15 spm，不插值读数会明显跳档。
        val fineBin = bestK + SignalUtil.parabolicOffset(
            spectrum[bestK - 1], spectrum[bestK], spectrum[bestK + 1]
        )
        var f0 = fineBin * binHz

        // 8) 谐波校正：手持摆臂谐波丰富，若主峰偏高且 f0/2 处能量足够，
        //    说明可能误取了二次谐波。
        if (f0 > harmonicCheckHz) {
            val subBin = (f0 / 2.0) / binHz
            val subK = subBin.toInt()
            if (subK in kMin..kMax) {
                val peakAmp = spectrum[bestK]
                if (peakAmp > 0.0 && spectrum[subK] / peakAmp >= harmonicSubRatio) {
                    f0 = subBin * binHz
                }
            }
        }

        return Result(
            spectralSpm = (f0 * 60.0).toFloat(),
            peakSpm = peakSpm,
            rms = energy,
            prominence = prominence,
            cv = cvOf(intervals),
            peakCount = peaks.size
        )
    }

    private fun medianIntervalToSpm(list: List<Double>): Float? {
        if (list.size < 3) return null
        val sorted = list.sorted()
        val med = sorted[sorted.size / 2]
        if (med <= 0.0) return null
        return (60.0 / med).toFloat()
    }

    private fun cvOf(list: List<Double>): Float {
        if (list.size < 2) return 1f
        var mean = 0.0
        for (v in list) mean += v
        mean /= list.size
        if (mean <= 0.0) return 1f
        var acc = 0.0
        for (v in list) {
            val d = v - mean
            acc += d * d
        }
        return (sqrt(acc / list.size) / mean).toFloat()
    }

    /** 供测试与调试使用：最近一次幅度谱的拷贝。 */
    fun lastSpectrum(): DoubleArray = spectrum.copyOf()

    companion object {
        fun isPlausible(spm: Float): Boolean = spm >= 60f && spm <= 180f && !spm.isNaN()
        fun relativeDiff(a: Float, b: Float): Float {
            if (a == 0f) return Float.MAX_VALUE
            return abs(a - b) / a
        }
    }
}
