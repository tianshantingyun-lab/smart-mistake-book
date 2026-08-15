# REST 接口契约

所有时间使用 ISO 8601，所有 JSON 响应使用 UTF-8。管理接口通过同站点安全会话认证；文件上传使用 `multipart/form-data`。当前前端使用本地 Mock，以下路径是接入真实服务时的稳定契约。

## 公共接口

| 方法 | 路径 | 响应 |
| --- | --- | --- |
| `GET` | `/api/v1/public/state` | 首屏聚合的 `PublicWebsiteState`；结构中不存在任何草稿字段 |
| `GET` | `/api/v1/public/settings` | 已发布 `SiteContent` |
| `GET` | `/api/v1/public/pages/:slug` | 已发布 `ManagedPageContent`；未发布返回 `404` |
| `GET` | `/api/v1/public/releases/latest?channel=stable\|beta` | 最新已发布 `Release` 或 `204` |
| `GET` | `/api/v1/public/docs` | 仅包含已发布文档的分层目录 |
| `GET` | `/api/v1/public/docs/:slug` | 已发布 `DocumentationContent`；未发布返回 `404` |
| `GET` | `/api/v1/public/changelog` | 已发布版本及其 `releaseNotes`，不维护第二份日志 |
| `GET` | `/api/v1/public/product` | 已发布 `ProductPageContent`；草稿或归档状态返回 `404` |

公共响应不得包含草稿、演示会话或未发布内容。

`PublicWebsiteState` 只包含已发布的站点内容、静态页面、版本、文档、公开媒体和可空的产品页内容。它不是 `WebsiteState` 的裁剪约定，而是独立 DTO；服务端不得先序列化完整管理状态再依赖客户端过滤。客户端会严格拒绝包含未知 `draft` 字段的公共响应。

## 管理接口

| 方法 | 路径 | 请求 / 作用 |
| --- | --- | --- |
| `GET` | `/api/v1/admin/session` | 查询同站点安全会话；未登录返回 `401` |
| `POST` | `/api/v1/admin/session/demo` | 未来替换演示会话的入口，返回 `AdminSession` |
| `DELETE` | `/api/v1/admin/session` | 注销当前会话，成功返回 `204` |
| `GET` | `/api/v1/admin/state` | 返回经过认证的完整 `WebsiteState`，包括草稿 |
| `POST` | `/api/v1/admin/demo/reset` | 仅演示环境恢复初始数据 |
| `PUT` | `/api/v1/admin/site/draft` | 保存不含 `analytics` 的站点展示草稿；请求体为 `Omit<SiteContent, "analytics">` |
| `POST` | `/api/v1/admin/site/preview` | 创建只读预览 |
| `POST` | `/api/v1/admin/site/publish` | 可选携带不含 `analytics` 的站点展示内容并在同一事务中保存、发布；无请求体时发布已保存草稿 |
| `PUT` | `/api/v1/admin/analytics/draft` | 请求体仅为 `{ providerName, siteId }`，只保存分析配置草稿 |
| `POST` | `/api/v1/admin/analytics/publish` | 只发布分析配置草稿，不得改动品牌、首屏、按钮或信任说明 |
| `PUT` | `/api/v1/admin/pages/:id/draft` | 保存 `ManagedPageContent` 草稿 |
| `POST` | `/api/v1/admin/pages/:id/preview` | 创建静态页面预览 |
| `POST` | `/api/v1/admin/pages/:id/publish` | 可选携带 `ManagedPageContent` 并在同一事务中保存、发布；必须拒绝标题、摘要、正文或 SEO 信息不完整的内容 |
| `POST` | `/api/v1/admin/pages/:id/archive` | 立即下线公开页并保留草稿 |
| `PATCH` | `/api/v1/admin/media/:id` | 字段级更新，请求体只允许 `label`、`alt`、`visible` 中实际变化的字段；隐藏被当前已发布产品页可见区块引用的媒体时返回 `409` |
| `POST` | `/api/v1/admin/media` | multipart：`file`、`label`、`alt`、`role` |
| `DELETE` | `/api/v1/admin/media/:id` | 删除未被产品页引用的上传资源；内置资源不可删除 |
| `PUT` | `/api/v1/admin/releases/:id` | 保存 `Release` 草稿元数据 |
| `POST` | `/api/v1/admin/releases/:id/package` | multipart：`file`，服务返回文件地址、大小与 SHA-256 |
| `POST` | `/api/v1/admin/releases/:id/preview` | 创建版本预览 |
| `POST` | `/api/v1/admin/releases/:id/publish` | 发布版本 |
| `POST` | `/api/v1/admin/releases/:id/archive` | 归档版本 |
| `PUT` | `/api/v1/admin/docs/:id` | 请求体仅为 `DocumentationContent`；新文档强制创建为草稿，已有文档只更新草稿与 `updatedAt` |
| `POST` | `/api/v1/admin/docs/:id/preview` | 创建文档预览 |
| `POST` | `/api/v1/admin/docs/:id/publish` | 可选携带 `DocumentationContent` 并在同一事务中保存、校验、发布；无请求体时发布已保存草稿 |
| `POST` | `/api/v1/admin/docs/:id/archive` | 归档文档 |
| `PUT` | `/api/v1/admin/product/draft` | 保存完整 `ProductPageContent` 草稿 |
| `POST` | `/api/v1/admin/product/preview` | 创建管理员专用整页预览 |
| `POST` | `/api/v1/admin/product/publish` | 校验并原子发布整页产品内容 |
| `POST` | `/api/v1/admin/product/archive` | 归档产品页并隐藏公开入口 |

### 管理快照版本与并发写入

`GET /api/v1/admin/state` 以及每个成功的管理写接口都必须同时返回：

- 完整且可通过当前 `WebsiteState` schema 校验的管理快照。
- 表示该快照版本的强 `ETag` 响应头，例如 `ETag: "content-42"`；不得使用 `W/` 弱验证器。

若内容 API 与官网跨源部署，CORS 还必须允许请求头 `If-Match`，并通过 `Access-Control-Expose-Headers: ETag` 向浏览器暴露响应版本；否则客户端会按缺失 `ETag` 进入重新同步状态。

客户端在一次成功的管理状态加载后，按调用顺序串行发送全部管理读取与写入。每个写请求都携带当前已确认版本的 `If-Match`，服务端必须在同一事务中检查版本、执行命令、生成新快照和新强 `ETag`。一次写入成功返回的新 `ETag` 是下一次写入的前置条件；客户端只有在 JSON 解析、完整 schema 校验和强 `ETag` 校验全部成功后才更新本地版本并广播快照。

`If-Match` 不再匹配时返回 `412 Precondition Failed`，不得提交命令。服务端无法接受当前写入前置条件时可以返回 `428 Precondition Required`，同样不得提交命令。业务规则冲突（例如隐藏仍被已发布产品页引用的媒体）继续返回 `409 Conflict`；服务端必须保证这类确定性 `4xx` 响应没有部分提交。客户端收到 `412/428`、不确定的网络错误、任意 `5xx`、成功响应中的非法 JSON、非法 schema、缺失或弱 `ETag` 后，会清除当前版本并进入“必须重新同步”状态；此时所有后续管理写入都在本地拒绝且不联网，直到新的 `GET /api/v1/admin/state` 返回合法快照和强 `ETag`。因此服务端不得把已经提交的写入包装成上述失败响应。

管理加载和写入共享 FIFO 队列，公共内容读取不进入该队列且在管理状态需要同步或授权失效时仍然可用。普通、明确且未提交的确定性业务 `4xx` 不会破坏队列；后续管理加载仍可执行。

管理接口以 `401 Unauthorized` 表示会话不存在或已经过期，以 `403 Forbidden` 表示会话仍可识别但管理权限已被撤销。前端收到任一管理内容接口的 `401/403` 时，会在读取错误正文前同步广播授权失效、立即丢弃内存中的完整管理快照，并且只重新加载公开状态；同一轮并发失败只执行一次注销与公开重载，迟到的管理响应不得恢复草稿。显式注销、运行时授权失效，以及显式登录成功但管理快照恢复失败，都会在当前标签页的 `sessionStorage` 写入 fail-closed 标记。因此即使远程注销请求失败，刷新当前标签页也必须跳过会话探测并保持公开态。此后的“重新尝试”也只读取公开接口，只有一次新的显式登录及管理状态加载全部成功后才能清除标记并恢复管理态。存储不可用时，当前挂载仍须以内存标记保持 fail-closed；不得因为持久化失败重新显示管理快照。公共接口的 HTTP 错误不得触发这一管理授权广播。

REST 内容适配器以授权 epoch 隔离失效前后的队列项：任一 `401/403` 都会立即清除当前 `ETag` 和待使用的恢复授权，并使旧 epoch 中尚未发出的管理请求失效；同一失效周期只广播一次授权错误。授权已失效期间新建的管理读取与写入都不得联网。只有操作者重新完成显式登录后，认证控制器才同步调用 `confirmAdminAuthentication()` 授予一次恢复加载，并随后请求管理状态；登录请求失败时严禁调用该信号。

恢复信号会建立新授权 epoch、清除旧 `ETag` 并要求重新同步。这份一次性授权在恢复加载开始联网时即被消费；若该加载发生网络错误、`5xx`、非法 JSON/schema/ETag 或其他失败，后续管理加载仍须在本地拒绝，操作者必须重新显式登录取得新授权。恢复加载只有在 HTTP、JSON、完整 schema 和强 `ETag` 全部有效时才解除恢复门控与 revoked 状态，并再建立一个已确认 epoch。恢复信号前直接发起的管理加载必须在本地拒绝，失效期间或恢复完成前提前排队的写命令也不会自动执行。每个管理请求在网络响应到达后、解析或广播快照前都必须再次核对其捕获的授权 epoch；新的显式登录开始后，旧请求的迟到响应不得更新 `ETag`、广播状态或撤销新会话。

`GET /api/v1/admin/session` 的 `401/403` 均按未登录处理并返回空会话；`DELETE /api/v1/admin/session` 的 `401/403` 均视为已经完成注销。登录入口的 `401/403` 仍是需要向操作者明确展示的失败，不得伪装成登录成功。

除会话注销外，当前管理写接口成功后统一返回完整、最新且通过 `WebsiteState` schema 校验的管理快照。若后端未来改用 `204`，必须作为新契约版本发布，并由客户端显式重新请求 `/api/v1/admin/state`；不得返回空响应却继续宣称兼容当前适配器。

保存文档草稿的请求和服务端写入边界不得接受 `status`、`published` 或 `publishedAt`。发布文档携带内容时，保存草稿、校验与生成公开快照必须属于同一事务，禁止由客户端先保存再发第二个发布请求；`slug` 必须已经规范化为可由单段 `/docs/:slug` 访问的路径，并且不得与其他已发布文档的规范化路径重复。`parentId` 只能引用已发布文档，不得指向自身、缺失或未发布的文档，也不得形成多节点循环。任一校验失败都必须同时保持原草稿与公开版本不变。归档只改变发布状态并使文档立即从公共接口消失，不删除其草稿或历史已发布内容；仍有已发布直接子文档时必须以 `409` 拒绝归档，避免产生孤立公开目录。

文档 `order` 必须是大于或等于 `0` 的整数。版本 `versionCode` 必须是大于或等于 `1` 的整数，`minAndroid` 必须是大于或等于 `6` 的整数；`versionName` 与 `releaseNotes` 均为必填。客户端适配器会在发出 Local 或 REST 写命令前校验，服务端仍必须独立执行相同规则，并在失败时原子保留原状态。

站点展示内容与分析配置使用双向隔离的独立命令。站点保存不得读取或覆盖 `site.draft.analytics`，站点发布不得改动 `site.published.analytics`；分析保存只能更新 `site.draft.analytics`，分析发布只能把该字段复制到 `site.published.analytics`，不得连带发布尚未批准的首页或品牌草稿。站点或静态页面发布携带内容时，保存与生成公开快照必须属于同一事务，禁止“先保存、再发布”的可交错实现。统计平台名称与站点 ID 必须同时填写或同时清空；完整配置只有在隐私页已发布时才允许发布，二者均清空时允许发布以关闭分析。服务端必须再次执行这些门控校验并在失败时保持管理状态和公开状态不变。

媒体修改使用字段级 PATCH：服务端必须在事务内读取该 ID 的最新资源，只把请求中的 `label`、`alt`、`visible` 合并进去，不能用客户端旧快照覆盖 `src`、`role`、`bundled`、Blob 元数据或其他并发写入。`label` 与 `alt` 会去除首尾空白，合并后的二者均不得为空；请求出现其他字段时返回 `400`。客户端网关把空 changes 视为 no-op，不发送请求。

媒体可见性也是发布完整性的一部分：当产品页状态为 `published` 时，只要媒体 ID 被其任一 `enabled: true` 区块引用，服务端就必须拒绝把该媒体从可见改为隐藏，并以 `409 Conflict` 返回明确原因。仅存在于产品草稿或已发布内容中 `enabled: false` 区块的引用不阻止隐藏。冲突响应不得提交同一 PATCH 中的名称、替代文本或可见性修改。

本地演示模式使用浏览器存储和演示会话。配置 `VITE_CONTENT_API_BASE_URL` 后，登录、内容读取和写操作都会发送到远程服务；远程模式不得显示“数据仅保存在当前浏览器”或提供本地恢复初始数据操作。

真实生产服务发布版本时必须同时满足：

- 文件为 APK，扩展名、版本、渠道和文件大小均通过校验。
- `package.storageState` 为 `stored`。
- `package.downloadUrl` 是真实可访问的存储地址。
- `package.sha256` 是 64 位十六进制 SHA-256。

缺少真实地址或校验值时，发布接口必须返回 `422`，不得生成虚假下载链接。演示 Mock 可以把元数据版本标记为已发布，但公共页只显示不可下载状态。

## 稳定分析事件

`page_view`、`download_intent`、`docs_search`、`contact_link`。

事件只有在统计平台与站点 ID 已配置、已发布隐私说明存在且用户同意为 `granted` 时才允许发送；撤回同意后立即停止。
