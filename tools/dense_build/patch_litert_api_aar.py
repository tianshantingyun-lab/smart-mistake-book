# -*- coding: utf-8 -*-
"""把 `com.google.ai.edge.litert:litert-api` 的 AAR 清单**只改 namespace**，其余逐字节保留。

## 为什么需要

LiteRT 2.1.6 的两个 AAR（`litert` 与 `litert-api`）在各自的 `AndroidManifest.xml` 里
**声明了同一个 namespace `com.google.ai.edge.litert`**，而 app 模块的 manifest 合并
（AGP 9.3.0 的 `ManifestMerger2#checkUniqueNamespaces`）把"两个库共用 namespace"判为
错误：

```
Namespace 'com.google.ai.edge.litert' is used in multiple modules and/or libraries:
com.google.ai.edge.litert:litert:2.1.6, com.google.ai.edge.litert:litert-api:2.1.6.
```

两个 AAR 都是必需的（`litert` 提供 `org.tensorflow.lite.Interpreter` 等 16 个类 +
`libLiteRt.so`；`litert-api` 提供 `InterpreterApi`/`Tensor`/`TensorFlowLite` 等被前者
引用的类 + `liblitert_jni.so`），所以只能给其中一个换 namespace。`litert-api` 的 AAR
**没有 res/**（只有 classes.jar、jni/、proguard.txt），改 namespace 不牵动资源或 R 类。

## 做法（确定性）

读源 AAR → 只在 `AndroidManifest.xml` 里把 `package="com.google.ai.edge.litert"`
换成 `package="com.google.ai.edge.litert.api"` → 其余条目按原顺序、原内容写回。
脚本打印源/产物 sha256 与清单改动前后，便于复核"只改了这一处"。

用法（仓库根下）：
```
python tools/dense_build/patch_litert_api_aar.py <源 litert-api-2.1.6.aar> <产物路径>
```
"""
from __future__ import annotations

import hashlib
import re
import sys
import zipfile
from pathlib import Path

SOURCE_NAMESPACE = "com.google.ai.edge.litert"
PATCHED_NAMESPACE = "com.google.ai.edge.litert.api"
MANIFEST_ENTRY = "AndroidManifest.xml"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def patch(source: Path, target: Path) -> None:
    with zipfile.ZipFile(source) as archive:
        entries = [(info, archive.read(info.filename)) for info in archive.infolist()]
    manifest = None
    for info, data in entries:
        if info.filename == MANIFEST_ENTRY:
            manifest = data.decode("utf-8")
    if manifest is None:
        raise SystemExit("AAR 里没有 %s" % MANIFEST_ENTRY)
    if f'package="{SOURCE_NAMESPACE}"' not in manifest:
        raise SystemExit("清单里没有 package=\"%s\"，源件可能已变" % SOURCE_NAMESPACE)
    if 'package="' in manifest.replace(f'package="{SOURCE_NAMESPACE}"', ""):
        raise SystemExit("清单里出现第二个 package 属性，拒绝改写")
    patched = manifest.replace(f'package="{SOURCE_NAMESPACE}"',
                               f'package="{PATCHED_NAMESPACE}"', 1)
    print("manifest: %s" % re.search(r'<manifest[^>]*>', manifest).group(0)[:160])
    print("     ->  %s" % re.search(r'<manifest[^>]*>', patched).group(0)[:160])

    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
        for info, data in entries:
            if info.filename == MANIFEST_ENTRY:
                data = patched.encode("utf-8")
            new_info = zipfile.ZipInfo(info.filename, date_time=(1980, 1, 1, 0, 0, 0))
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            out.writestr(new_info, data)
    print("source  sha256 = %s (%d bytes)" % (sha256_file(source), source.stat().st_size))
    print("patched sha256 = %s (%d bytes)" % (sha256_file(target), target.stat().st_size))
    with zipfile.ZipFile(target) as check:
        names_src = [i.filename for i in zipfile.ZipFile(source).infolist()]
        names_dst = [i.filename for i in check.infolist()]
        if names_src != names_dst:
            raise SystemExit("条目集合被改动，拒绝：%s vs %s" % (names_src, names_dst))
        unchanged = all(
            check.read(name) == dict((i.filename, data) for i, data in entries)[name]
            for name in names_dst if name != MANIFEST_ENTRY
        )
        print("除 %s 外逐条字节相同：%s" % (MANIFEST_ENTRY, unchanged))
        if not unchanged:
            raise SystemExit("有其它条目被改动，拒绝")


if __name__ == "__main__":
    patch(Path(sys.argv[1]), Path(sys.argv[2]))
