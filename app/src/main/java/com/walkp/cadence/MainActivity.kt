package com.walkp.cadence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkp.cadence.ui.CadenceScreen
import com.walkp.cadence.ui.DetailScreen
import com.walkp.cadence.ui.HistoryScreen
import com.walkp.cadence.ui.ThemeMode
import com.walkp.cadence.ui.WalkPTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CadenceViewModel by viewModels()

    // 通知权限只影响记录时通知栏是否可见，不阻断记录本身
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 亮屏常亮：这是“边走边看”的场景，屏幕熄灭就等于没法读数
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 边到边绘制，配合 Compose 侧的 safeDrawingPadding 避开状态栏/挖孔
        enableEdgeToEdge()

        // UI 只是测量的观察者：前台保证 engine 在跑；退到后台且未记录时停掉省电。
        // 正在记录时 engine 的生命周期由前台服务负责，与 UI 无关。
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = viewModel.uiForeground()
            override fun onPause(owner: LifecycleOwner) = viewModel.uiBackground()
        })

        setContent {
            var themeMode by remember { mutableStateOf(loadThemeMode()) }
            WalkPTheme(themeMode) {
                val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
                val placement by viewModel.placement.collectAsStateWithLifecycle()
                val recording by viewModel.recording.collectAsStateWithLifecycle()
                val recordingStartMs by viewModel.recordingStartMs.collectAsStateWithLifecycle()
                val screen by viewModel.screen.collectAsStateWithLifecycle()
                val sessions by viewModel.sessions.collectAsStateWithLifecycle()
                val detail by viewModel.detail.collectAsStateWithLifecycle()

                when (screen) {
                    Screen.MAIN -> CadenceScreen(
                        snapshot = snapshot,
                        sensorAvailable = viewModel.sensorAvailable,
                        placement = placement,
                        recording = recording,
                        recordingStartMs = recordingStartMs,
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            saveThemeMode(mode)
                        },
                        onPlacementChange = viewModel::setPlacement,
                        onStartRecording = {
                            ensureNotificationPermission()
                            viewModel.beginRecording()
                        },
                        onStopRecording = { viewModel.endRecording() },
                        onOpenHistory = viewModel::openHistory
                    )
                    Screen.HISTORY -> {
                        BackHandler { viewModel.back() }
                        HistoryScreen(
                            sessions = sessions,
                            onBack = viewModel::back,
                            onOpenDetail = viewModel::openDetail,
                            onDelete = viewModel::deleteSession
                        )
                    }
                    Screen.DETAIL -> {
                        BackHandler { viewModel.back() }
                        DetailScreen(detail = detail, onBack = viewModel::back)
                    }
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadThemeMode(): ThemeMode {
        val name = getPreferences(Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(name!!) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private fun saveThemeMode(mode: ThemeMode) {
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
