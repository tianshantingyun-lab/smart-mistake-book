# -*- coding: utf-8 -*-
"""dense 向量资产门（`tools/dense_build/check_asset.py`）的**正反两侧**用例。

它盯的失败很具体：**包或词表内容变了、而 `.vec` 没跟着重生成**。这类失败在现有 22 门里
完全不可见（那些门只查包的自身契约），而端侧会拿"旧内容的向量"去比"新词条的文本"，
检索分数整体失真——所以门必须能红，且红得指得出是哪一处不一致。

- 正侧：真资产（仓库当前状态）⇒ 空 `failed`；
- 反侧 1：**包被改过**（在临时副本里加一个别名）⇒ `packSha256` 必须红；
- 反侧 2：**词表被改过** ⇒ `vocab:*` 必须红；
- 反侧 3：**.vec 头/行数与旁车不符** ⇒ `vectorHeader`/`vectorCount` 必须红。

反侧全部在临时副本上做（不碰真资产）。
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

from dense_build import check_asset  # noqa: E402
from dense_build import dense_asset as D  # noqa: E402


class DenseAssetGateTest(unittest.TestCase):

    def setUp(self):
        self.vector = REPO.joinpath(*D.DENSE_DIR_RELATIVE.split("/")) / D.VECTOR_FILE_NAME
        self.sidecar = self.vector.with_name(self.vector.name + ".json")
        self.pack = REPO.joinpath(*D.PACK_RELATIVE.split("/"))
        self.vocab = REPO.joinpath(*D.VOCAB_RELATIVE.split("/"))
        self.tmp = Path(tempfile.mkdtemp(prefix="dense-gate-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _failed_names(self, result):
        return sorted(check["name"] for check in result["failed"])

    def _tamper_pack(self, filename: str) -> Path:
        """复制一份包并把**第一条别名加到第一个非空 topic 的第一个知识点上**。

        包里有空 topic（0 个知识点），所以不能写死 `topics[0].knowledgePoints[0]`——
        首版就是这么写而 IndexError 的。
        """
        copy = self.tmp / filename
        payload = json.loads(self.pack.read_text(encoding="utf-8"))
        for subject in payload["subjects"]:
            for topic in subject["topics"]:
                if topic["knowledgePoints"]:
                    point = topic["knowledgePoints"][0]
                    point["aliases"] = list(point.get("aliases") or []) + ["新增别名-向量里没有"]
                    copy.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
                    return copy
        raise AssertionError("包里找不到任何非空 topic——测试夹具失效")

    def test_realAssetPassesAllChecks(self):
        """正侧：真资产必须全绿（任一红都说明资产与当前包/词表已经不同源）。"""
        result = check_asset.evaluate(REPO)
        self.assertTrue(result["ok"], "dense 资产门在真资产上应全绿，实际红：%r"
                        % [(c["name"], c["detail"]) for c in result["failed"]])
        names = [check["name"] for check in result["checks"]]
        for expected in ("packSha256", "vocab:vocab", "vocab:tokenizer", "vectorSha256",
                         "vectorHeader", "vectorCount", "vectorBytes", "layout"):
            self.assertIn(expected, names, "门漏掉了检查项 %s" % expected)

    def test_packChangedButVectorNotRegeneratedIsRed(self):
        """反侧 1：包内容变了（加一条别名）而向量没重生成 ⇒ `packSha256` 与 `layout` 必须红。"""
        pack_copy = self._tamper_pack("pack.json")
        self.assertNotEqual(D.sha256_file(pack_copy), D.sha256_file(self.pack),
                            "测试前提：改过的包 sha256 必须与原包不同")
        result = check_asset.evaluate(REPO, pack_path=pack_copy)
        self.assertFalse(result["ok"], "包内容变了，门必须红")
        failed = self._failed_names(result)
        self.assertIn("packSha256", failed)
        self.assertIn("layout", failed)

    def test_packAliasAddedIsCaughtByLayoutEvenIfHashesMatch(self):
        """反侧 1b：改包 + 把旁车哈希同步成新包 ⇒ 仍必须被 `layout` 抓住。

        这是这门存在的核心理由：光比哈希，可以"改包 + 改旁车哈希"骗过去；比 ids 与当前包
        布局才把"这份向量属于这一版包"钉死。
        """
        pack_copy = self._tamper_pack("pack2.json")
        sidecar_copy = self.tmp / "fake.json"
        faked = json.loads(self.sidecar.read_text(encoding="utf-8"))
        faked["packSha256"] = D.sha256_file(pack_copy)  # 把哈希"改对"——哈希层被绕过
        sidecar_copy.write_text(json.dumps(faked, ensure_ascii=False, indent=2), encoding="utf-8")
        result = check_asset.evaluate(REPO, pack_path=pack_copy, sidecar_path=sidecar_copy)
        self.assertFalse(result["ok"], "包布局变了（多一条别名）而向量没重生成：即便旁车哈希被同步，门也必须红")
        failed = self._failed_names(result)
        self.assertIn("layout", failed)
        self.assertNotIn("packSha256", failed, "这一例的前提是哈希层已被绕过，它不该红")

    def test_vocabChangedIsRed(self):
        """反侧 2：词表被改过 ⇒ `vocab:*` 必须红。"""
        vocab_copy = self.tmp / "vocab.txt"
        vocab_copy.write_bytes(self.vocab.read_bytes() + b"\n[UNK]\n")
        result = check_asset.evaluate(REPO, vocab_path=vocab_copy)
        self.assertFalse(result["ok"], "词表变了，门必须红")
        self.assertIn("vocab:vocab", self._failed_names(result))

    def test_vectorCountMismatchInSidecarIsRed(self):
        """反侧 3：旁车自述的行数与 `.vec` 头不符 ⇒ `vectorCount` 必须红。"""
        sidecar_copy = self.tmp / "count.json"
        payload = json.loads(self.sidecar.read_text(encoding="utf-8"))
        payload["corpus"]["vectorCount"] = payload["corpus"]["vectorCount"] + 1
        sidecar_copy.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        result = check_asset.evaluate(REPO, sidecar_path=sidecar_copy)
        self.assertFalse(result["ok"], "旁车行数与向量头不符，门必须红")
        self.assertIn("vectorCount", self._failed_names(result))

    def test_missingSidecarIsRed(self):
        """反侧 4：缺旁车（资产没被生成/没入库）⇒ 红，且不抛异常。"""
        result = check_asset.evaluate(REPO, sidecar_path=self.tmp / "nope.json")
        self.assertFalse(result["ok"])
        self.assertIn("sidecar", self._failed_names(result))


if __name__ == "__main__":
    unittest.main()
