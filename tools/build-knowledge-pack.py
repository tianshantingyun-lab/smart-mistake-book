#!/usr/bin/env python3
"""知识包构建与校验入口。

- --verify-roundtrip  证明生成器能忠实重放现行成品（不改任何文件）
- --gate              跑内容质量门，输出缺陷归零指标（不改任何文件）

生成器（--build）已随 kb_build.build 停用；写盘拓扑改为 staging → promote：
手术工具写 build/kb-staging/，`kb_build.promote` 门全绿后晋升到成品目录。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from kb_build import gate, pack_io, roundtrip


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-roundtrip", action="store_true",
                        help="校验生成器对现行成品可忠实重放")
    parser.add_argument("--gate", action="store_true",
                        help="跑内容质量门并打印缺陷指标")
    parser.add_argument("--json", action="store_true",
                        help="把 --gate 结果按 JSON 输出")
    parser.add_argument("--root", default=None,
                        help="读取根（默认 staging；干净检出上即成品镜像）")
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(Path(args.root).resolve())

    if args.verify_roundtrip:
        return roundtrip.main()
    if args.gate:
        return gate.main(as_json=args.json)
    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
