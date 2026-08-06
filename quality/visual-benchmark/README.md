# 复杂题图基准

这个模块负责验证真实题图数据集的覆盖面，并统计两家多模态模型在“最多修复一次”后的结构化场景合规率。它不会把学生原图、答案或 API 密钥写入仓库。

## 私有数据布局

原图和真实标注放在仓库外的私有目录。清单只使用相对路径和 SHA-256；运行校验时会确认路径没有越界、文件存在且哈希一致。

发布门槛固定为：

- 至少 120 张不同原图；
- 至少 9 个高中学科；
- 至少 12 类图形；
- 每例明确标注当前小问、对象、连接、方向、可见数值来源、空间关系和时间顺序；不适用的类别保留空列表；
- 可见数值来源只能是题面给出或严格推导；
- 每家模型最多有一次修复机会，修复后合规生成率不低于 95%；
- 至少两家不同 Provider。

## 命令

```powershell
.\gradlew.bat :quality:visual-benchmark:run --args="validate D:\private-benchmark\manifest.json D:\private-benchmark"
.\gradlew.bat :quality:visual-benchmark:run --args="score D:\private-benchmark\manifest.json D:\private-benchmark\provider-runs.json"
```

`validate` 是真实发布门槛，不会因缺少本地数据而静默跳过。`score` 除语义标注比对外，还要求场景通过生产级本地来源校验与编译，且没有可见的示意数值；旧的 provider-run 文件没有来源校验结果时会安全判为不合格。

五张首批图片只用于本机人工标注和验收；未经明确同意，不复制到 Git，也不上传到模型基准仓库。
