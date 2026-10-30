package com.walkp.cadence

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.walkp.cadence.data.AppDatabase
import com.walkp.cadence.data.SessionRecorder
import com.walkp.cadence.service.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进程级单例：engine 与录制状态的唯一所有者。
 *
 * 为什么不是 ViewModel 持有 engine：
 * 后台长时间记录的生命周期主人是【前台服务】，不是 UI。
 * UI 打开只是观察，关掉 UI（甚至退回桌面）都不该影响记录。
 * 所以 engine 挂在这里，Activity 和 Service 都只是它的"使用者"。
 */
object CadenceServiceManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var engine: CadenceEngine? = null
    private var forwardJob: Job? = null
    private var recorder: SessionRecorder? = null
    private var appContext: Context? = null

    private val _snapshot = MutableStateFlow(CadenceEngine.Snapshot())
    val snapshot: StateFlow<CadenceEngine.Snapshot> = _snapshot.asStateFlow()

    private val _placement = MutableStateFlow(Placement.HAND)
    val placement: StateFlow<Placement> = _placement.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _recordingStartMs = MutableStateFlow<Long?>(null)
    val recordingStartMs: StateFlow<Long?> = _recordingStartMs.asStateFlow()

    val sensorAvailable: Boolean get() = engine?.sensorAvailable ?: true

    @Synchronized
    fun ensureStarted(context: Context) {
        if (engine == null) {
            val ctx = context.applicationContext
            appContext = ctx
            val e = CadenceEngine(ctx)
            e.setPlacement(_placement.value)
            engine = e
            forwardJob = scope.launch {
                e.snapshot.collect { _snapshot.value = it }
            }
        }
        engine?.start()
    }

    /** Activity 回到前台：保证 engine 在跑，供实时显示。 */
    fun uiForeground(context: Context) = ensureStarted(context)

    /** Activity 退到后台：没在记录就停掉 engine 省电。 */
    @Synchronized
    fun uiBackground() {
        if (!_recording.value) engine?.stop()
    }

    fun setPlacement(p: Placement) {
        _placement.value = p
        engine?.setPlacement(p)
    }

    @Synchronized
    fun beginRecording(context: Context) {
        if (_recording.value) return
        ensureStarted(context)
        val ctx = appContext ?: context.applicationContext
        val e = engine ?: return
        val rec = SessionRecorder(AppDatabase.get(ctx))
        recorder = rec
        rec.start(scope, e.secondFlow)
        _recording.value = true
        _recordingStartMs.value = System.currentTimeMillis()
        ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java))
    }

    fun endRecording(context: Context, onSaved: ((Long?) -> Unit)? = null) {
        if (!_recording.value) return
        val rec = recorder
        recorder = null
        _recording.value = false
        _recordingStartMs.value = null
        val p = _placement.value
        scope.launch {
            val id = rec?.stop(p)
            if (onSaved != null) withContext(Dispatchers.Main) { onSaved(id) }
        }
        val ctx = context.applicationContext
        ctx.stopService(Intent(ctx, RecordingService::class.java))
    }

    /** 服务被系统回收等异常情况：结束记录，保住已经采到的数据。 */
    fun onServiceDestroyed() {
        if (!_recording.value) return
        val rec = recorder
        recorder = null
        _recording.value = false
        _recordingStartMs.value = null
        val p = _placement.value
        scope.launch { rec?.stop(p) }
    }
}
