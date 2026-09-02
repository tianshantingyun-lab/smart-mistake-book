# image-mcp-server

独立 MCP server 进程：把被拍摄的题目照片重绘成干净的题面图。
内部调用 OpenAI `POST /v1/images/edits`（gpt-image-2），通过 MCP 以工具形式暴露。

这是图像管线唯一图生图来源：宿主（Claude Code 等）或 app 侧的 MCP client 调用
`redraw_clean_problem`，传入原图，拿回干净图。

## 工具

| 工具 | 输入 | 输出 |
|---|---|---|
| `redraw_clean_problem` | `source_image_b64`(原图 base64)、`source_mime_type`(image/jpeg/png/webp) | JSON：`status`、`image_b64`(干净图 base64)、`mime_type`、`model` |

内部 prompt（固定）：保留印刷题面文字与图形不变，仅去除手写笔迹、涂改、无关阴影与折痕，
重绘成干净题面图；不改写、增删或重新排版任何印刷内容。

## 运行

要求：JDK 21+（构建用 Kotlin 2.4 / Gradle 9.7）。启动需 `OPENAI_API_KEY`（缺失时 exit 2）。

```bash
export OPENAI_API_KEY=sk-...
java -jar build/libs/image-mcp-server-0.1.0-all.jar     # stdio transport
```

### 构建

```bash
./gradlew.bat shadowJar    # Windows
./gradlew shadowJar        # 其它
```

产物：`build/libs/image-mcp-server-0.1.0-all.jar`

### 接入 Claude Code

```bash
claude mcp add figure-redraw --env OPENAI_API_KEY=sk-... -- java -jar /绝对路径/image-mcp-server-0.1.0-all.jar
```

### 用 MCP Inspector 手动验证

```bash
npx @modelcontextprotocol/inspector -- java -jar build/libs/image-mcp-server-0.1.0-all.jar
```

## 验证

- `./gradlew.bat test`：MockWebServer 单测 `OpenAiEditsClientTest`（3 例：edits 协议、
  HTTP 失败、空响应）
- stdio 握手探针（本仓库 scripts）：`initialize` + `tools/list` 应列出 `redraw_clean_problem`

## 结构

- `OpenAiEditsClient.kt` — `/v1/images/edits` multipart 客户端（从 app 已验证的
  `OpenAiImageGenerationChannel` 移植，字节级 multipart 解析测试）
- `ImageMcpServer.kt` — MCP server 入口 + `redraw_clean_problem` 工具注册
