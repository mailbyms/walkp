package com.walkp.cadence.data

import com.walkp.cadence.Placement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 会话记录器：订阅引擎的每秒样本流，结束时落库。
 *
 * 设计取向：样本先在内存里攒着，结束时一次性事务写入。
 * 一小时会话 = 3600 条样本，内存里只有 ~50 KB，远比每秒一次磁盘 IO 省电。
 */
class SessionRecorder(private val db: AppDatabase) {

    private var collectJob: Job? = null
    private var startMs: Long = 0L
    private val samples = ArrayList<Pair<Long, Float?>>()

    val isActive: Boolean get() = collectJob != null

    fun start(scope: CoroutineScope, secondFlow: SharedFlow<Float?>) {
        if (isActive) return
        startMs = System.currentTimeMillis()
        samples.clear()
        collectJob = scope.launch {
            secondFlow.collect { v ->
                samples.add(((System.currentTimeMillis() - startMs) / 1000L) to v)
            }
        }
    }

    /** 结束并写库，返回会话 id；没有样本则返回 null。 */
    suspend fun stop(placement: Placement): Long? {
        collectJob?.cancel()
        collectJob = null
        if (samples.isEmpty()) return null

        val endMs = System.currentTimeMillis()
        val valid = samples.mapNotNull { it.second }
        val avg = if (valid.isNotEmpty()) valid.average().toFloat() else null
        val min = valid.minOrNull()
        val max = valid.maxOrNull()
        val steps = (valid.sum() / 60.0).toInt()

        val session = SessionEntity(
            startMs = startMs,
            endMs = endMs,
            placement = placement.name,
            durationSec = samples.size,
            validSec = valid.size,
            avgSpm = avg,
            minSpm = min,
            maxSpm = max,
            estimatedSteps = steps
        )

        val dao = db.sessionDao()
        val sessionId = dao.insertSession(session)
        dao.insertSamples(
            samples.map { (sec, spm) ->
                SampleEntity(sessionId = sessionId, epochSec = sec, spm = spm)
            }
        )
        return sessionId
    }
}
