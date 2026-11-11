# VAP KMP

独立的 VAP / VAPX Compose Multiplatform 播放库。三端使用同一套 Compose Canvas 绘制；不嵌入 AndroidView、UIKitView 或 ArkUIView 播放器。当前为本地 `0.1.0-SNAPSHOT`，尚未公开发布 Maven。Android专用`android-frame`是同版本的传递依赖；发布配置已包含JNI方法保留规则，尚未执行发布。

## 性能报告

[在线查看网页版性能报告](https://rejigtian.github.io/kmp-vap/performance/2026-11-10/report.html) · [Markdown版](docs/performance/2026-11-10/README.md) · [下载HTML离线版](docs/performance/2026-11-10/report.html)

Android原机单路基准：优化前Canvas、当前Canvas与原生的CPU、帧率、PSS及Graphics对照。报告标明测试批次、下降幅度与测量边界。网页版通过GitHub Pages直接打开；Markdown可在仓库内阅读，HTML离线版下载后用浏览器打开。

```kotlin
VapImage(
    source = "/absolute/path/gift.mp4",
    modifier = Modifier.size(240.dp),
    playing = visible,
    loopCount = 1,
    dynamicContent = VapDynamicContent(
        images = mapOf("[avatar]" to avatarPath),
        texts = mapOf("[nickname]" to nickname),
    ),
    onFinished = { /* 全部循环自然完成 */ },
    onError = { error -> /* 资源、配置或解码失败 */ },
)
```

替换键必须与素材原始 `srcTag` 一致，包含素材自带的方括号。缺少必需 tag 会报错。支持 VAP v2 的 `vapc` 配置、RGB/Alpha 区域、VAPX 图片/文字、逐帧 z 顺序、遮罩和 0/90 度遮罩方向；不猜测无配置 MP4 的排列。当前静音播放。

`loopCount=0` 无限循环，正数为总次数。`playing=false` 准备首帧，播放后则冻结当前帧；宿主后台也暂停。source、dynamicContent、loopCount 或 fileResolver 改变会重建播放，回调变更不重播。`scaleType` 支持 Fit/Crop/Fill。移除、换源或失败不会伪造 `onFinished`。

## 共享和平台边界

- `commonMain`：流式 MP4 box / vapc 读取与协议解析；资源 tag 校验；PTS 播放时钟、暂停/恢复、循环与事件；Canvas RGB/Alpha 合成；VAPX 图文绘制和逐帧遮罩；缩放与裁剪。
- Android：MediaExtractor + MediaCodec 顺序解码；OES 原始 packed 帧适配，不执行 VAP 合成。API29+ 经小型EGL/HardwareBuffer桥接，一次GPU绘制到独立packed帧；不创建逐帧HardwareBufferRenderer/RenderNode，不经过ImageReader中转。输出在GPU写入完成后交给共享Canvas，不复用仍在消费的帧；API23–28使用pbuffer + glReadPixels批量适配。帧退休时显式回收Bitmap和缓冲句柄，不依赖GC释放像素。
- iOS：AVAssetReader 输出 BGRA，按行 native memcpy 到 Skia Bitmap；没有 UIImage/UIView/Metal 播放器。
- HarmonyOS：Kotlin/Native 直接调用系统 AVSource/AVDemuxer/VideoDecoder 的 buffer 模式。优先 RGBA，NV12/NV21 由系统 Pixelmap 转换后直写 Skia 像素；不逐像素执行 Kotlin 转换。没有 ArkTS、XComponent、HAR 或自带 C++ 渲染引擎。

视频帧按需顺序解码并背压，不把整段 MP4 读进 ByteArray、不做 PNG/JPEG 中转、不缓存整段帧。共享层按Canvas实际记录帧推进退休边界，最多保留3帧；平台帧拥有独立像素存储，已提交的绘制命令自行持有底层图像引用。硬件解码的具体支持取决于系统设备，不能用编译结果推断帧率与功耗。

替换图片保留原始解码尺寸、色彩和透明度，不做缩小采样。每个`VapImage`实例有独立的图片LRU缓存，预算8MiB（按编码快照、像素格式/尺寸及条目开销估算，并非进程或GPU内存上限）。完整编码内容决定命中，哈希碰撞仍逐字节比较；同路径、相同长度和修改时间的内容变更也会失效。编码文件超过2MiB或解码结果超预算时沿用正常加载，不驻留缓存。缓存跨本实例的图文替换/重新准备复用，随实例离开组合清空引用；淘汰不回收仍可能被绘制使用的图像。视频帧不进入图片缓存。

文字沿用素材颜色、粗体和区域，由共享 TextMeasurer 排版；默认系统字体在平台间可能存在字形差异。`centerFull` 图片沿素材固定资源尺寸居中裁剪，`fitXY` 拉伸到融合区域。

## 文件与网络

Android/iOS 默认接受本地绝对路径、file URL 和 HTTP(S)，网络资源写入系统可清理的磁盘缓存。MP4 不经过整段 ByteArray。可以传入 `VapFileResolver` 复用应用下载、鉴权和缓存失效策略；该 resolver 用于主视频及 HTTP(S) 替换图片。

HarmonyOS 当前基线 API12 默认接受本地绝对路径和 file URL；网络视频、网络图片必须提供 `VapFileResolver` 返回本地绝对路径。没有为使用较新的系统 HTTP 接口而静默提升 API 基线。不再需要安装、注册配套 HAR。

## 构建与消费

Android/iOS：Kotlin 2.2.21、Compose 1.10.3、AGP 9.1.1；Android minSdk23、iOS13。Android帧适配桥接模块使用NDK28.2.13676358、CMake3.22.1，通过androidMain依赖自动打包四种默认ABI；它只导入EGL帧目标，不解析VAP或绘制播放器View。iOS 只需 Kotlin/Native 系统框架，不再编译或静态链接旧 ObjC 引擎。macOS 建议 ARM JDK。

```sh
./gradlew :vap:testAndroidHostTest
./gradlew :vap:linkDebugFrameworkIosArm64 :vap:linkDebugFrameworkIosSimulatorArm64
```

```kotlin
// settings.gradle.kts
includeBuild("/path/to/vap-kmp") {
    dependencySubstitution {
        substitute(module("io.github.vapkmp:vap")).using(project(":vap"))
    }
}
// commonMain.dependencies
api("io.github.vapkmp:vap:0.1.0-SNAPSHOT")
```

HarmonyOS 使用独立 `ohos-build`、Gradle8.9、Kotlin2.2.21-1.1.0、Compose1.9.2-1.1.0。坐标为 `io.github.vapkmp:vap-ohos:0.1.0-SNAPSHOT`，隔离两套 Kotlin metadata。

```sh
cd ohos-build
./gradlew :vap-ohos:compileKotlinOhosArm64
```

三端编译、iOS Framework 链接、Android 安装、实际播放和性能测量分别验证；鸿蒙 Pixelmap 的 NV12/NV21 转换及各端解码颜色仍需对应设备确认。

Android 修改后执行以下检查，覆盖 API 23 兼容性扫描及共享层主机测试；编译或高版本手机播放通过不能替代低版本兼容性检查。

```sh
./gradlew :vap:lint :android-frame:lint :vap:testAndroidHostTest
```

Android 主代码报告位于 `vap/build/reports/lint-results-androidMain.html`。`vap` 显式启用 `com.android.lint`，其 `check` 任务包含 Lint；API 29 硬件缓冲路径由版本判断隔离，API 23–28 使用软件帧适配。
