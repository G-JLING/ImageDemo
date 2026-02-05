# ImageDemo (HDR → SDR)

一个 HDR → SDR 解码的演示项目，保留最小 Spring Boot 启动用于工程结构，但默认不启动 Web Server。

## 外部工具

此项目使用这些工具，您需要在运行环境中安装这些工具：

- FFmpeg（含 ffprobe）
- Exiftool
- Heif-convert

提供依赖检查脚本：

```bash
scripts/check_deps.sh
```

## Java 环境

- Java 17 或更新版本。

## 目录结构

```
src/main/java/me/jling/imagedemo
└── image
    └── core
        ├── sdr        # HDR -> SDR 解码
        └── tool       # 元信息读取
```

## 配置项

位置：`src/main/resources/application.properties`

核心配置：

- `heif.cli.ffmpegPath`（含 `ffprobe`）
- `heif.cli.exiftoolPath`
- `heif.cli.heifConvertPath`
- `heif.cli.timeout-sec`（默认 120 秒）
- `heif.cli.gainmapMergePy`（可选增益图合成脚本）

## 独立运行（推荐）

```bash
./mvnw -q -DskipTests package
java -cp target/classes me.jling.imagedemo.image.core.sdr.HdrSdrCli input.heic output.jpg
```

可通过系统属性覆盖工具路径，例如：

```bash
java -Dheif.cli.ffmpegPath=/opt/homebrew/bin/ffmpeg \
     -Dheif.cli.ffprobePath=/opt/homebrew/bin/ffprobe \
     -Dheif.cli.exiftoolPath=/opt/homebrew/bin/exiftool \
     -Dheif.cli.heifConvertPath=/opt/homebrew/bin/heif-convert \
     -cp target/classes me.jling.imagedemo.image.core.sdr.HdrSdrCli input.heic output.jpg
```

## 说明

- HEIF/AVIF 优先走 ffprobe/ffmpeg/heif-convert 兜底解码，避免网格切片被误判为缩略图。
- HDR 识别依赖元数据线索（Gain Map、容器特征等）。
