# 爽读爽听

本地优先的**阅读 + 听书**应用。看书、听书都在自己手机上完成：书本地存，音频本地转本地播，
唯一联网的是听书合成（调用你自己在 [Fish Audio](https://fish.audio) 的密钥）和检查更新。

## 阅读

- 支持 **TXT**（UTF-8 / GBK / GB18030 / UTF-16，带不带 BOM 都能读）与 **EPUB**（EPUB2 / EPUB3，正文插画会一起显示）
- 自动分章，章节表可跳转，进度自动保存
- 四种翻页方式：平移 / 覆盖 / 上下滑动 / 无动画，拖动跟手、跨章连续
- 五套皮肤：纸感 / 墨绿 / 暗粉 / 墨蓝 / 纯黑，书架和阅读页一起换
- 字号、行距、段间距、边距、亮度、字体、首行缩进全部可调
- 书架多选（批量删除 / 移入分组 / 批量转音频）、分组管理、目录批量导入、外部文件直接打开

## 听书（基于 Fish Audio）

- 在 app 里填入自己的 **Fish Audio API Key**（用 AndroidKeyStore AES/GCM 加密，只存本机，不上传），模型默认 `s2.1-pro-free`（免费档，按并发限流、无每日上限），也可选付费模型
- 音色从社区音色库挑选：可搜索、可试听、可收藏，支持全局默认音色 + 每本书单独覆盖
- **按段落合成**：正文按段落切分、超长自动再拆，纯符号段自动跳过；章节标题也一起转成语音
- **断点续转**：只补缺失的段，中断 / 杀进程 / 限流暂停之后都从缺口接着跑，不会重头
- **限流保护**：触发 429 自动整队退避（5s→10s→20s→40s→60s 封顶）并提示，并发 1~7 可调
- **播放**：逐段高亮 + 自动翻页到正在读的那句，悬浮球可暂停 / 定位 / 显示倒计时 / 退出，倍速 1.0~2.0，定时停止
- 书架多选**批量转音频**，封面上实时显示进度；音频按书本地保存，可查看占用、单独删除
- 一本书播完自动接下一章（下一章已转好的前提下）

## 其它

- 内置 GitHub Releases 自动检查更新，下载后直接安装，不用再手动传 APK

## 编译

需要 JDK 17 + Android SDK（compileSdk 34）。

```bash
# local.properties 里写 sdk.dir
./gradlew assembleRelease
```

签名信息放在项目根目录的 `keystore.properties`（已被 .gitignore 排除）：

```properties
storeFile=release.jks
storePassword=***
keyAlias=shuangdu
keyPassword=***
```

## 自动更新

应用启动时（或手动点「检查更新」）请求：

```
https://api.github.com/repos/AnSleeper/shuangdu/releases/latest
```

拿 `tag_name` 跟当前 `versionName` 比大小，取 `assets` 里的 `.apk` 下载后调起系统安装器。
API 被限流时会自动改走 Release 里的 `version.json`（CDN，不占 API 配额）。
仓库坐标写在 `app/build.gradle.kts` 的 `buildConfigField` 里，换仓库改两行即可。

> 更新能否装上取决于签名一致：必须一直用同一个 `release.jks` 打包。

## 目录

```
app/src/main/java/com/shuangdu/reader/
├── data/        书架、设置、导入与章节读取
├── format/      TXT 编码识别、分章、EPUB 解析
├── ui/          书架、阅读页、翻页、听书面板与弹层
├── update/      GitHub 更新检查与安装
└── tts/         Fish Audio 客户端、密钥加密存储、段落切分、转换队列、播放器
```

导入时会把内容统一转成 UTF-8 的 `content.txt` + 章节字节索引 `index.json` 存在应用私有目录，
之后翻页只做一次随机读，跟原始文件编码无关，也不依赖原文件是否还在。
听书音频存在每本书的 `tts/` 目录下（`index.json` 记录段落与音频的对应关系），删书时一并删除。
