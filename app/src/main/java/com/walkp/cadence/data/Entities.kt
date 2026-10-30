package com.walkp.cadence.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一次连续记录的会话。 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    /** "HAND" / "POCKET"，存字符串避免枚举顺序变化破坏历史数据 */
    val placement: String,
    /** 总秒数 */
    val durationSec: Int,
    /** 有效（判定为步行）秒数 */
    val validSec: Int,
    /** 有效秒的平均步频 */
    val avgSpm: Float?,
    val minSpm: Float?,
    val maxSpm: Float?,
    /** 估算总步数 = Σ(每秒 spm) / 60 */
    val estimatedSteps: Int
)

/** 每秒一个样本，null 表示该秒未检测到步行。 */
@Entity(
    tableName = "session_samples",
    indices = [Index("sessionId")]
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 相对会话开始的秒数 */
    val epochSec: Long,
    val spm: Float?
)
