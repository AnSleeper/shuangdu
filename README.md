# 爽读爽听

一个纯本地阅读器。只看书、只听书，不联网（除了检查更新）。

- 支持 **TXT**（UTF-8 / GBK / GB18030 / UTF-16，带不带 BOM 都能读）与 **EPUB**（EPUB2 / EPUB3，正文插画会一起显示）
- 自动分章，章节表可跳转，进度自动保存
- 四种翻页方式：平移 / 覆盖 / 上下滑动 / 无动画，拖动跟手、跨章连续
- 五套皮肤：纸感 / 墨绿 / 暗粉 / 墨蓝 / 纯黑，书架和阅读页一起换
- 字号、行距、边距、字体、首行缩进全部可调
- 书架多选（批量删除 / 移入分组）、分组管理、目录批量导入、外部文件直接打开
- 内置 GitHub Releases 自动检查更新，下载后直接安装，不用再手动传 APK

听书功能预留了接口（`tts/TtsEngine.kt`），下一版接入。

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
仓库坐标写在 `app/build.gradle.kts` 的 `buildConfigField` 里，换仓库改两行即可。

> 更新能否装上取决于签名一致：必须一直用同一个 `release.jks` 打包。

## 目录

```
app/src/main/java/com/shuangdu/reader/
├── data/        书架、设置、导入与章节读取
├── format/      TXT 编码识别、分章、EPUB 解析
├── ui/          书架、阅读页、翻页、弹层
├── update/      GitHub 更新检查与安装
└── tts/         听书接口（预留）
```

导入时会把内容统一转成 UTF-8 的 `content.txt` + 章节字节索引 `index.json` 存在应用私有目录，
之后翻页只做一次随机读，跟原始文件编码无关，也不依赖原文件是否还在。
