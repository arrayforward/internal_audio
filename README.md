# 内部录音机（Internal Audio Capture）

基于 Android 10 `AudioPlaybackCapture` API 的**内部音频录制**应用：无需麦克风，直接录制系统/其他 App 播放的声音，内置波形播放器与音频剪辑器。

[![Release](https://img.shields.io/github/v/release/arrayforward/internal_audio)](https://github.com/arrayforward/internal_audio/releases/latest)

## 功能特性

- 🎙️ **内部录音**：捕获系统媒体/游戏音频（`AudioPlaybackCapture` + `MediaProjection`）
- 🎚️ **高音质**：48kHz / 16bit / 立体声
- 💾 **多格式**：WAV（无损）、M4A（AAC 192kbps）；MP3 预留 LAME 扩展位（当前自动降级 M4A）
- 📂 **自动保存**：随机文件名 `recording_xxxxxx.wav/m4a`，保存至 `/sdcard/Music/audio/`
- ▶️ **应用内播放器**：波形示意图 + 播放进度线，点按波形任意位置跳转
- ✂️ **音频剪辑**：波形拖拽裁剪头尾片段，支持试听选区、保存副本 / 覆盖保存
  - WAV：字节级裁剪，零重编码、零损耗
  - M4A：`MediaExtractor` + `MediaMuxer` 帧级裁剪
- 📊 **PESQ 质量分析**：剪辑页一键评估语音质量（电平/信噪比/削波/静音 → MOS 1.0~4.5 分）
- 📝 **文件管理**：重命名（扩展名锁定）、删除（二次确认）
- 🌙 **Material 3 主题**：深色/浅色自动适配，实时录音波形动画 + 计时器

## 下载安装

从 [Releases](https://github.com/arrayforward/internal_audio/releases/latest) 下载最新 APK 直接安装。

首次使用需授权：
1. **麦克风权限**（系统要求，实际不采集麦克风）
2. **所有文件访问权限**（Android 11+/HarmonyOS，用于写入 Music 目录）
3. **系统录屏授权**（每次开始录音时弹出，Android 平台强制要求）

## 使用说明

> 📖 更详细的手把手教程见 [docs/UserGuide.md](docs/UserGuide.md)

### 首次授权（仅一次）
1. **麦克风权限** → 选择“使用应用时允许”（系统强制要求，实际不采集麦克风）
2. **通知权限**（Android 13+）→ 允许，用于录音时显示前台状态
3. **所有文件访问权限**（Android 11+/HarmonyOS）→ 点击录音按钮时按弹窗指引，去系统设置开启后返回

### 录音
1. 主界面下拉选择输出格式（WAV 无损 / M4A 高音质小体积）
2. 先播放要录制的声音（音乐、视频、游戏等）
3. 点击 **● 录音**，在系统录屏授权窗口点“立即开始”（只取声音，不录画面）
4. 录制中：波形实时跳动、计时器走动、通知栏常驻，可切到后台
5. 点击 **⏹ 停止**，自动生成 `recording_xxxxxx.wav/m4a` 保存到 `/sdcard/Music/audio/`

### 播放（应用内播放器）
1. 列表点 **▶️** 进入播放页，显示完整波形示意图
2. 点底部 **▶️/⏸** 控制播放暂停，蓝色竖线实时指示位置
3. **点按波形任意位置**即可跳转播放

### 剪辑（去头尾）
1. 列表点 **✂️** 进入剪辑页，等待波形加载
2. 拖动波形两端红色手柄（或下方双滑块）调整保留范围，灰色部分将被裁掉
3. 点 **🔊 试听选区** 确认效果，**🔄 重置选区** 可恢复全选
4. 右上角选择 **保存为副本**（推荐，保留原文件）或 **覆盖保存**（替换原文件，有二次确认）

### PESQ 质量分析
1. 在剪辑页点击 **PESQ 分析** 按钮，后台自动解码评估
2. 弹窗显示评分（1.0~4.5）与等级（优秀/良好/一般/较差），以及有效电平、估算信噪比、削波比例、静音比例
3. 说明：标准 PESQ（ITU-T P.862）需原始参考语音，本功能为非侵入式估算，结果仅供参考

### 文件管理
- ✏️ **重命名**：只能改主文件名，扩展名锁定
- 🗑️ **删除**：二次确认后从 `/sdcard/Music/audio/` 移除，不可恢复

## 环境要求

| 项目 | 要求 |
|------|------|
| 最低系统 | Android 10（API 29） |
| 编译 SDK | API 34 |
| Gradle | 8.9 |
| AGP | 8.5.2 |
| Kotlin | 1.9.24 |
| JDK | 17+ |

## 本地构建

```bash
git clone https://github.com/arrayforward/internal_audio.git
cd internal_audio
./gradlew assembleDebug
```

输出：`app/build/outputs/apk/debug/internal_audio-v1.0-debug.apk`

> 注：targetSdk 29 是为保留传统存储行为；release 构建已禁用 `ExpiredTargetSdkVersion` lint 检查。

## 项目结构

```
app/src/main/java/com/arrayforward/audiocapture/
├── MainActivity.kt          # 主界面：权限、格式选择、录音控制、文件列表
├── AudioCaptureService.kt   # 前台服务：MediaProjection + AudioRecord 采集
├── WavEncoder.kt            # WAV 编码（流式写入 + RIFF 头回填）
├── AacEncoder.kt            # AAC 编码（MediaCodec + MediaMuxer → .m4a）
├── PlayerActivity.kt        # 应用内播放器（波形 + 进度线 + 点按跳转）
├── TrimActivity.kt          # 剪辑界面（选区、试听、副本/覆盖保存、PESQ 分析）
├── AudioTrimmer.kt          # 裁剪引擎（WAV 字节级 / M4A 帧级 / 波形峰值提取 / PCM 解码）
├── PesqAnalyzer.kt          # PESQ 风格语音质量评估（非侵入式 MOS 估算）
├── TrimWaveformView.kt      # 波形 View（剪辑选区手柄 / 播放只读两种模式）
├── WaveformView.kt          # 录音实时波形 View
├── RecordingBus.kt          # 录音状态/振幅事件总线（LiveData）
├── RecordingAdapter.kt      # 文件列表适配器
├── FileUtils.kt             # 目录、命名、格式化工具
└── AudioParams.kt           # 音频参数常量（48kHz/16bit/立体声/192kbps）
```

## 技术要点

- **内部录音**：`AudioPlaybackCaptureConfiguration` 匹配 `USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN`，通过 `AudioRecord` 读取 PCM；目标 App 声明 `allowAudioPlaybackCapture="false"` 时无法捕获其声音
- **存储兼容**：`targetSdk 29` + `requestLegacyExternalStorage`（Android 10）；Android 11+/HarmonyOS 申请 `MANAGE_EXTERNAL_STORAGE`
- **WAV 剪辑**：按 `byteRate` 比例截取 PCM 数据块（块对齐），重写 RIFF 头，毫秒级完成
- **M4A 剪辑**：`SEEK_TO_PREVIOUS_SYNC` 定位起点，逐帧拷贝重封装，时间戳重定基

完整设计文档见 [docs/AudioCapture_Design.md](docs/AudioCapture_Design.md)。

## License

MIT
