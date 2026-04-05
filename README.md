# Whisper 会议转写 APK

这个工程现在已经切到真正的 `whisper.cpp` Android 路线，不再使用系统自带语音识别。

## 这版是什么

- Android 原生 APK
- 本地离线录音转文字
- 直接使用 `whisper.cpp` JNI
- 面向 `medium` 和 `large-v3` 模型
- 模型文件单独放在手机本地，不打进 APK

## 为什么模型不直接塞进 APK

`medium` 和 `large-v3` 体积很大，直接打进 APK 不现实，安装包会过大、构建慢、更新也不方便。

所以这版采用：

- `APK` 负责界面、录音、Whisper 推理
- 模型文件单独下载到本地
- 第一次启动后在 App 里手动导入模型文件

## 你的设备建议

基于你说的两档机器：

- 8 Gen 2 / 12 GB：能跑 `medium`，更推荐 `medium-q5_0`
- 8 Elite / 16 GB：能尝试 `large-v3`，更推荐先从 `large-v3-q5_0` 开始验证速度和发热

如果你坚持要原版模型：

- `medium`
- `large-v3`

这版 App 也支持导入它们。

## 构建 APK

在根目录运行：

```bash
chmod +x build.sh
./build.sh
```

输出：

```text
build/whisper-meeting-transcriber.apk
```

## 下载模型

下载原版 `medium` 和 `large-v3`：

```bash
chmod +x download-models.sh
./download-models.sh exact
```

下载更适合手机的量化版：

```bash
./download-models.sh mobile
```

模型会保存到：

```text
models/
```

## 手机上的使用方式

1. 安装 APK
2. 把模型文件放到手机本地任意可访问位置
3. 打开 App
4. 点击“导入本地模型文件”
5. 选择 `ggml-medium*.bin` 或 `ggml-large-v3*.bin`
6. 加载完成后开始录音
7. 结束录音后，App 会本地离线转写

## 当前已完成

- `whisper.cpp` 官方 Android 示例已成功编译
- JNI / CMake / NDK 构建链已跑通
- App 已改成模型导入与本地选择
- App 名称已改成中文
- 构建目标已收敛到 `arm64-v8a`

## 产物位置

Gradle 原始产物：

```text
vendor/whisper.cpp/examples/whisper.android/app/build/outputs/apk/release/app-release.apk
```

根目录复制产物：

```text
build/whisper-meeting-transcriber.apk
```
