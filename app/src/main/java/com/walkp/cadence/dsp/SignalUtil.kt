package com.walkp.cadence.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 步频估计用到的一维信号处理小工具。
 * 全部为纯函数 / 原地操作，方便离线回放时单独做单元测试。
 */
object SignalUtil {

    /**
     * 把时间戳不均匀的原始样本，线性重采样到 [n] 个等间隔点上。
     *
     * @param srcT 单调递增的时间，单位秒（任意零点均可）
     * @param srcV 与 srcT 对应的取值
     * @param count srcT / srcV 中有效元素个数
     * @param out 输出缓冲，长度必须 >= n
     * @return 实际写入的点数（等于 n），或 -1 表示输入不足
     */
    fun resampleUniform(
        srcT: DoubleArray,
        srcV: DoubleArray,
        count: Int,
        out: DoubleArray,
        n: Int
    ): Int {
        if (count < 2) return -1
        val t0 = srcT[0]
        val t1 = srcT[count - 1]
        val span = t1 - t0
        if (span <= 0.0) return -1

        val step = span / (n - 1)
        var readIdx = 0
        for (i in 0 until n) {
            val t = t0 + step * i
            while (readIdx < count - 2 && srcT[readIdx + 1] < t) readIdx++
            val ta = srcT[readIdx]
            val tb = srcT[readIdx + 1]
            val va = srcV[readIdx]
            val vb = srcV[readIdx + 1]
            out[i] = if (tb == ta) va else va + (vb - va) * ((t - ta) / (tb - ta))
        }
        return n
    }

    /** 原地去除最小二乘拟合直线（同时去掉直流分量与缓慢漂移）。 */
    fun detrend(x: DoubleArray, count: Int = x.size) {
        if (count < 2) return
        var sumT = 0.0
        var sumV = 0.0
        for (i in 0 until count) {
            sumT += i.toDouble()
            sumV += x[i]
        }
        val meanT = sumT / count
        val meanV = sumV / count
        var num = 0.0
        var den = 0.0
        for (i in 0 until count) {
            val dt = i - meanT
            num += dt * (x[i] - meanV)
            den += dt * dt
        }
        val slope = if (den == 0.0) 0.0 else num / den
        for (i in 0 until count) {
            x[i] -= meanV + slope * (i - meanT)
        }
    }

    /** 汉宁窗，长度 n。 */
    fun hann(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) {
            w[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
        }
        return w
    }

    /**
     * 抛物线插值：在离散峰 [idx] 处估计真实峰相对 idx 的偏移（单位：bin）。
     *
     * 4 秒窗的 FFT 分辨率只有 0.25 Hz（= 15 spm），不做插值读数会明显跳档。
     */
    fun parabolicOffset(y1: Double, y2: Double, y3: Double): Double {
        val den = y1 - 2.0 * y2 + y3
        if (den == 0.0) return 0.0
        var d = 0.5 * (y1 - y3) / den
        if (d > 0.5) d = 0.5
        if (d < -0.5) d = -0.5
        return d
    }

    /** 均方根（用于判断信号能量是否处于“正在走路”的量级）。 */
    fun rms(x: DoubleArray, count: Int = x.size): Double {
        if (count <= 0) return 0.0
        var acc = 0.0
        for (i in 0 until count) acc += x[i] * x[i]
        return sqrt(acc / count)
    }

    /**
     * 时域峰值检测：局部极大 + 自适应阈值 + 不应期。
     *
     * @param x 已去趋势的等间隔序列
     * @param fs 采样率 Hz
     * @param minIntervalSec 两次峰之间的最小间隔（0.25 s → 上限 240 spm）
     * @return 峰所在的时间（秒），升序
     */
    fun detectPeaks(
        x: DoubleArray,
        count: Int = x.size,
        fs: Double,
        minIntervalSec: Double = 0.25
    ): DoubleArray {
        if (count < 3) return DoubleArray(0)

        var mean = 0.0
        for (i in 0 until count) mean += x[i]
        mean /= count
        var varAcc = 0.0
        for (i in 0 until count) {
            val d = x[i] - mean
            varAcc += d * d
        }
        val std = sqrt(varAcc / count)
        val threshold = mean + 0.45 * std

        val minGap = (minIntervalSec * fs).toInt().coerceAtLeast(1)
        val result = ArrayList<Double>(32)
        var lastIdx = -minGap
        for (i in 1 until count - 1) {
            if (x[i] > threshold && x[i] >= x[i - 1] && x[i] > x[i + 1]) {
                if (i - lastIdx >= minGap) {
                    result.add(i / fs)
                    lastIdx = i
                }
            }
        }
        return result.toDoubleArray()
    }
}
