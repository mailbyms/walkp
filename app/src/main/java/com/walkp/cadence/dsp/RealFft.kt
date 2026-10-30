package com.walkp.cadence.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 极简 radix-2 迭代式 FFT。
 *
 * 只为 200~300 点的短序列服务，不追求极致性能，但保证正确且无依赖。
 * 输入输出均为原地（in-place），长度必须是 2 的幂。
 */
object RealFft {

    /** 原地复数 FFT。re / im 长度相同且为 2 的幂。 */
    fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(im.size == n) { "re/im length mismatch" }
        require(n >= 2 && (n and (n - 1)) == 0) { "length must be a power of two, got $n" }

        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // 蝶形运算
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            val half = len shr 1
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half]
                    val bIm = im[i + k + half]
                    val vRe = bRe * curRe - bIm * curIm
                    val vIm = bRe * curIm + bIm * curRe
                    re[i + k] = aRe + vRe
                    im[i + k] = aIm + vIm
                    re[i + k + half] = aRe - vRe
                    im[i + k + half] = aIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * 实数输入 → 幅度谱。
     * [input] 长度必须为 2 的幂；[out] 长度必须为 input.size / 2 + 1。
     * 幅度未做归一化，绝对值无意义，只用于比较相对大小。
     */
    fun magnitudeSpectrum(input: DoubleArray, out: DoubleArray) {
        require(out.size == input.size / 2 + 1) {
            "out must be n/2+1, got ${out.size} for n=${input.size}"
        }
        val re = input.copyOf()
        val im = DoubleArray(input.size)
        fftInPlace(re, im)
        for (k in out.indices) {
            out[k] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k])
        }
    }
}
