package com.walkp.cadence

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.walkp.cadence.data.AppDatabase
import com.walkp.cadence.data.SampleEntity
import com.walkp.cadence.data.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 顶层界面导航：主屏 / 历史列表 / 会话详情。 */
enum class Screen { MAIN, HISTORY, DETAIL }

/**
 * ViewModel 只做两件事：
 *  1. 转发 CadenceServiceManager 的状态与操作（engine 的所有权在 manager，不在 UI 层）
 *  2. 历史会话查询与界面导航
 */
class CadenceViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.get(application).sessionDao()

    // ---- 实时测量状态（来自 manager，服务后台记录时同样持续更新） ----
    val snapshot: StateFlow<CadenceEngine.Snapshot> = CadenceServiceManager.snapshot
    val placement: StateFlow<Placement> = CadenceServiceManager.placement
    val recording: StateFlow<Boolean> = CadenceServiceManager.recording
    val recordingStartMs: StateFlow<Long?> = CadenceServiceManager.recordingStartMs
    val sensorAvailable: Boolean get() = CadenceServiceManager.sensorAvailable

    // ---- 历史 ----
    val sessions: StateFlow<List<SessionEntity>> =
        dao.observeSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _screen = MutableStateFlow(Screen.MAIN)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _detail = MutableStateFlow<Pair<SessionEntity, List<SampleEntity>>?>(null)
    val detail: StateFlow<Pair<SessionEntity, List<SampleEntity>>?> = _detail.asStateFlow()

    // ---- 操作 ----
    fun uiForeground() = CadenceServiceManager.uiForeground(getApplication())
    fun uiBackground() = CadenceServiceManager.uiBackground()

    fun setPlacement(p: Placement) = CadenceServiceManager.setPlacement(p)

    fun beginRecording() = CadenceServiceManager.beginRecording(getApplication())

    fun endRecording(onSaved: (Long?) -> Unit = {}) =
        CadenceServiceManager.endRecording(getApplication(), onSaved)

    fun openHistory() {
        _screen.value = Screen.HISTORY
    }

    fun openDetail(sessionId: Long) {
        viewModelScope.launch {
            val session = sessions.value.firstOrNull { it.id == sessionId } ?: return@launch
            val samples = dao.samplesFor(sessionId)
            _detail.value = session to samples
            _screen.value = Screen.DETAIL
        }
    }

    fun back() {
        when (_screen.value) {
            Screen.DETAIL -> _screen.value = Screen.HISTORY
            Screen.HISTORY -> _screen.value = Screen.MAIN
            Screen.MAIN -> Unit
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            dao.deleteSamplesFor(sessionId)
            dao.deleteSession(sessionId)
            if (_detail.value?.first?.id == sessionId) {
                _detail.value = null
                _screen.value = Screen.HISTORY
            }
        }
    }
}
