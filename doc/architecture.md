# 架构设计

## 模块划分

```
com.walkp.cadence
├── dsp/                        # 纯 Kotlin，零 Android 依赖，可离线单测
│   ├── RealFft.kt              #   radix-2 FFT（几十行，不引入大库）
│   ├── SignalUtil.kt           #   重采样、去趋势、汉宁窗、抛物线插值、峰值检测
│   └── CadenceEstimator.kt     #   核心估计算法（频域+时域+门控+谐波校正）
├── CadenceEngine.kt            # 采样缓冲、门控、融合平滑、60 秒环形统计
├── CadenceServiceManager.kt    # 进程级单例：engine 生命周期的主人
├── CadenceViewModel.kt         # UI 状态桥接（只是观察器）
├── Placement.kt                # 手持/口袋两套 EngineConfig
├── service/
│   └── RecordingService.kt     # health 类型前台服务，保活后台采集
├── data/
│   ├── AppDatabase.kt          # Room
│   ├── Entities.kt             #   SessionEntity + SampleEntity（每秒一个样本）
│   ├── SessionDao.kt
│   └── SessionRecorder.kt      # 内存攒样本，会话结束一次性写库
├── ui/
│   ├── CadenceScreen.kt        # 主屏：大数字 + 1 分钟平均 + 60 秒曲线 + 记录控制
│   ├── HistoryScreen.kt        # 历史列表 + 详情（统计 + 每分钟趋势）
│   ├── CadenceChart.kt         # 公共折线图组件（语义色，深浅色通用）
│   └── Theme.kt                # 浅色/深色/跟随系统
└── MainActivity.kt             # 导航、通知权限申请
```

## 关键设计：engine 的所有权属于服务，不属于 UI

这是支持「后台长时记录」的核心架构决策。

**错误做法**：ViewModel 持有 engine → UI 销毁（锁屏、退后台、旋转屏幕后进程重建）采集就断。

**正确做法**：进程级单例 `CadenceServiceManager` 持有 engine：

- UI 在前台且未记录 → `ensureStarted()`，正常采集
- UI 退到后台且未记录 → `stop()`，省电
- 用户点「开始记录」→ 启动 `RecordingService`（前台服务），engine 由服务保活，**UI 销毁不影响采集**
- UI 重新打开 → 自动接上实时读数（engine 的 StateFlow 一直在跑）

UI 永远只是 StateFlow 的观察器，不拥有任何采集资源。

## 前台服务合规（Android 14+）

- 类型：`foregroundServiceType="health"`
- 权限：`FOREGROUND_SERVICE_HEALTH`、`POST_NOTIFICATIONS`（Android 13+ 运行时申请，在开始记录时才弹）
- 通知：`IMPORTANCE_LOW`（无声音无震动），每 5 秒刷新一次当前步频
- 加速度计本身**不需要任何权限**

## 数据存储策略

记录期间每秒产出一个样本（时间戳 + 步频）。**不做逐秒写库**：

- 记录中：样本攒在内存（一小时 3600 条 ≈ 50 KB，毫无压力）
- 结束记录：一次性批量写入 Room

这个策略把磁盘 I/O 从「每秒一次」降到「每会话一次」，对长时间记录的耗电影响可忽略。

## 耗电预算

- 加速度计硬件本身非常省电，耗电大头是 CPU 常驻
- 分析频率 250 ms 一次，每次 256 点 FFT——现代手机可忽略
- 优化空间：注册传感器时用 `maxReportLatencyUs` 做批处理；后台记录时分析频率可降到 500 ms–1 s 一次（见「真机待验证」）

## 已知限制

- 国产 ROM（小米/华为/OPPO）对前台服务的后台限制各不相同，极端情况下服务可能被回收，需要引导用户把 App 加入自启动/后台白名单
- 息屏后部分机型传感器降采样，重采样逻辑对此天然兼容（按时间戳对齐），但长时间无事件时读数会进入等待态
