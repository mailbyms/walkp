package com.walkp.cadence

/** 手机放置位置。不同位置的信号特征差异很大，门控与谐波策略分开调。 */
enum class Placement(val label: String) {
    HAND("手持"),
    POCKET("口袋")
}

/**
 * 随位置变化的参数集。
 *
 * 为什么模值主频始终是步频而不是步态周期频率（步频的一半）：
 * 无论手持还是贴腿，手机跟随的肢体摆动主频其实是 stride 频率，
 * 但钟摆式摆动的【垂直】位移 ∝ 1-cosθ ≈ θ²/2，基频翻倍回步频；
 * 而模值里重力占主导，投影进去的就是垂直分量，所以模值主频 = 步频。
 * 两种位置的真实差异在【幅度】和【谐波丰富度】，不在主频位置。
 */
data class EngineConfig(
    val minRms: Double,
    val maxRms: Double,
    val minProminence: Float,
    val maxCv: Float,
    /** 高于此频率才检查是否误取二次谐波；设为很大值等于禁用 */
    val harmonicCheckHz: Double,
    val harmonicSubRatio: Double,
    val smoothAlpha: Float
)

object PlacementConfigs {

    fun forPlacement(p: Placement): EngineConfig = when (p) {
        // 手持：手臂末端摆幅大，信号强但谐波丰富 → 需要谐波校正
        Placement.HAND -> EngineConfig(
            minRms = 0.25,
            maxRms = 6.0,
            minProminence = 0.30f,
            maxCv = 0.18f,
            harmonicCheckHz = 2.4,
            harmonicSubRatio = 0.50,
            smoothAlpha = 0.35f
        )
        // 口袋：贴腿贴身，幅度明显更小、信号更接近正弦
        //  → RMS 下限放低、禁用谐波校正、节律性门限略放宽（口袋里会晃）
        Placement.POCKET -> EngineConfig(
            minRms = 0.12,
            maxRms = 6.0,
            minProminence = 0.30f,
            maxCv = 0.20f,
            harmonicCheckHz = 999.0,
            harmonicSubRatio = 0.50,
            smoothAlpha = 0.40f
        )
    }
}
