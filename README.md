# ImageDemo

基于 Spring Boot 的图片上传与 HDR/SDR 变体演示项目。前端为静态页面，放在
`src/main/resources/static`。

## 功能概览

- 多图上传
- HDR 线索识别（Gainmap/UltraHDR 等元数据）
- 仅生成两类变体：`sdr` 与 `hdr`
- 可选启动时清理本地存储，保证 Demo 干净

## 快速启动

```bash
./mvnw spring-boot:run
```

浏览器访问：`http://localhost:8080/`

## API

### 上传

`POST /api/images/upload`

- 表单字段：`files`（支持多文件）
- 可选参数：`source`（默认 `UPDATES`）

返回示例（每张图）：

```json
{
  "id": 1,
  "sdr": "/api/img/1/sdr",
  "hdr": "/api/img/1/secure-hdr",
  "isHdr": true
}
```

### 变体访问

- `GET /api/img/{id}/sdr`
- `GET /api/img/{id}/secure-hdr`
- `GET /api/img/{id}/hdr`（`secure-hdr` 的别名）

下载文件可加 `?download=1`：

- `/api/img/1/sdr?download=1`
- `/api/img/1/secure-hdr?download=1`

### 健康检查

- `GET /api/health`

## 配置项

位置：`src/main/resources/application.properties`

- `image.root`：本地存储路径（默认 `data/images`）
- `demo.storage.reset`：启动时清空 `image.root`（默认 `true`）
- `demo.dedupe.enabled`：是否按 sha256 去重（默认 `false`）
- `img.token.secret`：签名密钥（`/api/img/{id}/secure` 使用）
- `spring.servlet.multipart.max-file-size`
- `spring.servlet.multipart.max-request-size`

### 外部依赖（可选但推荐）

某些 HDR / HEIF / AVIF 处理会调用外部工具：

- `ffmpeg`
- `exiftool`
- `heif-convert`

对应路径配置：

- `heif.cli.ffmpegPath`
- `heif.cli.exiftoolPath`
- `heif.cli.heifConvertPath`

## 说明

- 前端预览使用浏览器的 HDR 管线，后端识别依赖文件内的元数据。
- 若拖拽过程中浏览器移除了 Gainmap 元信息，后端可能判为 SDR。
- Demo 场景建议保持 `demo.storage.reset=true`，避免旧数据干扰。
