package com.walkp.cadence

import com.walkp.cadence.dsp.CadenceEstimator
import com.walkp.cadence.dsp.RealFft
import com.walkp.cadence.dsp.SignalUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 用合成信号验证步频估计精度。
 *
 * 这是这个 App 最关键的一层验证：真机测试只能告诉你“大概对不对”，
 * 而合成信号已知真值，能直接量出误差。改任何参数后请先跑这个测试。
 */
class CadenceEstimatorTest {

    /**
     * 模拟手持走路的加速度模值：
     *   重力 9.8 + 步频基频 + 二次谐波（手臂摆动不是纯正弦）+ 宽频噪声
     * 时间戳带 ±15% 抖动，模拟 Android 传感器实际的不均匀采样。
     */
    private fun synth(
        spm: Double,
        seconds: Double = 4.2,
        fs: Double = 50.0,
        noise: Double = 0.10,
        subHarmonicRatio: Double = 0.0,
        amp: Double = 1.5,
        seed: Int = 1
    ): Pair<DoubleArray, DoubleArray> {
        val rnd = Random(seed)
        val f = spm / 60.0
        val t = ArrayList<Double>()
        val v = ArrayList<Double>()
        var cur = 0.0
        while (cur < seconds) {
            t.add(cur)
            var value = 9.8 +
                amp * sin(2 * PI * f * cur) +          // 基频 = 步频
                amp * 0.2 * sin(4 * PI * f * cur + 0.7) // 二次谐波
            if (subHarmonicRatio > 0.0) {
                // 左右脚不对称带来的步态周期（= 步频一半）分量
                value += amp * subHarmonicRatio * sin(PI * f * cur + 1.1)
            }
            value += (rnd.nextDouble() - 0.5) * noise
            v.add(value)
            cur += (1.0 / fs) * (1.0 + (rnd.nextDouble() - 0.5) * 0.3)
        }
        return t.toDoubleArray() to v.toDoubleArray()
    }

    private fun estimateSpm(pair: Pair<DoubleArray, DoubleArray>): Float? {
        val (t, v) = pair
        return CadenceEstimator().estimate(t, v, t.size).spectralSpm
    }

    @Test
    fun fftPeakIsAtExpectedBin() {
        val n = 256
        val fs = 64.0
        val x = DoubleArray(n) { i -> sin(2 * PI * 2.0 * i / fs) }
        val spec = DoubleArray(n / 2 + 1)
        RealFft.magnitudeSpectrum(x, spec)
        var best = 1
        for (k in 2 until spec.size) if (spec[k] > spec[best]) best = k
        assertEquals(8, best) // 2.0 Hz / (64/256 Hz per bin) = 8
    }

    @Test
    fun parabolicInterpolationIsSane() {
        // 对称时无偏移
        assertEquals(0.0, SignalUtil.parabolicOffset(1.0, 3.0, 1.0), 1e-9)
        // 右侧更高 → 峰偏右
        assertTrue(SignalUtil.parabolicOffset(1.0, 3.0, 2.0) > 0)
        // 左侧更高 → 峰偏左
        assertTrue(SignalUtil.parabolicOffset(2.0, 3.0, 1.0) < 0)
        // 偏移被限制在 ±0.5 bin
        assertTrue(abs(SignalUtil.parabolicOffset(0.0, 3.0, 2.99)) <= 0.5)
    }

    @Test
    fun resampleIsLinear() {
        val t = doubleArrayOf(0.0, 1.0, 2.0)
        val v = doubleArrayOf(0.0, 10.0, 20.0)
        val out = DoubleArray(5)
        SignalUtil.resampleUniform(t, v, 3, out, 5)
        assertEquals(0.0, out[0], 1e-9)
        assertEquals(5.0, out[1], 1e-9)
        assertEquals(10.0, out[2], 1e-9)
        assertEquals(15.0, out[3], 1e-9)
        assertEquals(20.0, out[4], 1e-9)
    }

    @Test
    fun recoversSlowWalkingCadence() {
        assertCadence(85.0)
    }

    @Test
    fun recoversNormalWalkingCadence() {
        assertCadence(100.0)
        assertCadence(115.0)
        assertCadence(120.0)
    }

    @Test
    fun recoversFastWalkingCadence() {
        assertCadence(140.0)
    }

    @Test
    fun doesNotHalveVeryFastCadence() {
        // 160 spm = 2.67 Hz，会触发谐波检查。
        // 正常情况下不应被误判成 80 spm。
        assertCadence(160.0)
    }

    @Test
    fun toleratesLeftRightAsymmetry() {
        // 加入 25% 的步态周期分量（左右脚不对称），不应影响步频估计
        val est = estimateSpm(synth(120.0, subHarmonicRatio = 0.25))
        assertEquals(120.0, est!!.toDouble(), 2.5)
    }

    @Test
    fun handlesOffGridFrequencies() {
        // 故意避开 bin 中心，检验抛物线插值的效果
        assertCadence(113.0)
        assertCadence(127.0)
    }

    /**
     * 打印一份精度报告，方便改参数后直接看误差量级（不是断言，只是观测）。
     * 运行：./gradlew :app:testDebugUnitTest --tests '*CadenceEstimatorTest*'
     */
    @Test
    fun printAccuracyReport() {
        val rows = StringBuilder()
        rows.appendLine("true_spm\testimated\terror_spm\tprominence\tcv")
        var maxErr = 0.0
        for (spm in listOf(80.0, 90.0, 100.0, 110.0, 115.0, 120.0, 125.0, 130.0, 140.0, 150.0, 160.0)) {
            val (t, v) = synth(spm)
            val r = CadenceEstimator().estimate(t, v, t.size)
            val est = r.spectralSpm
            val err = if (est == null) Double.NaN else abs(est - spm)
            if (!err.isNaN() && err > maxErr) maxErr = err
            rows.appendLine(
                String.format(
                    java.util.Locale.US,
                    "%.0f\t%.2f\t%.2f\t%.3f\t%.3f",
                    spm, est ?: Double.NaN, err, r.prominence, r.cv
                )
            )
        }
        rows.appendLine(java.lang.String.format(java.util.Locale.US, "max error = %.2f spm", maxErr))
        println(rows)
        assertTrue("最大误差 $maxErr 超出预期", maxErr <= 2.5)
    }

    @Test
    fun pocketModeSkipsHarmonicCorrection() {
        // 156 spm = 2.6 Hz，触发谐波检查阈值；子谐波比例 0.6 ≥ 0.50。
        // 手持模式：应当判定主峰是二次谐波，降到 78 spm；
        // 口袋模式（harmonicCheckHz 禁用）：应当保持 156 spm。
        val (t, v) = synth(156.0, subHarmonicRatio = 0.6)
        val hand = CadenceEstimator().estimate(t, v, t.size).spectralSpm
        val pocket = CadenceEstimator(harmonicCheckHz = 999.0).estimate(t, v, t.size).spectralSpm
        assertEquals(78.0, hand!!.toDouble(), 3.0)
        assertEquals(156.0, pocket!!.toDouble(), 3.0)
    }

    @Test
    fun pocketWeakSignalStillDetected() {
        // 口袋里信号幅度明显更小（0.6 vs 手持的 1.5），但估计精度不应变差。
        // 注意：能量门控在 Engine 层做（MIN_RMS 口袋 0.12 < 手持 0.25），
        // Estimator 本身不做门控，只验证弱信号下的频率精度。
        val est = estimateSpm(synth(120.0, amp = 0.6))
        assertEquals(120.0, est!!.toDouble(), 2.5)
    }

    private fun assertCadence(expected: Double, toleranceSpm: Double = 2.0) {
        val est = estimateSpm(synth(expected))
        assertTrue(
            "期望 $expected spm，实得 $est",
            est != null && abs(est - expected) <= toleranceSpm
        )
    }
}
