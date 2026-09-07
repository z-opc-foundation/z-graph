#!/usr/bin/env python3
"""
z-graph Bolt POC 端到端测试 — 用 neo4j Python driver 连接 z-graph BoltServer,执行 Cypher。

覆盖场景:
1. HELLO 握手
2. RETURN 整数 AS 别名
3. RETURN 字符串 AS 别名
4. RETURN 多列
5. RETURN 无别名
6. RETURN null / boolean
"""

import os
import sys
import time
import unittest

from neo4j import GraphDatabase


BOLT_URL = os.environ.get("Z_GRAPH_BOLT_URL", "bolt://localhost:7687")
AUTH = ("neo4j", "neo4j")  # POC 阶段服务端不校验 auth


class BoltPocE2ETest(unittest.TestCase):
    """端到端测试用例集"""

    @classmethod
    def setUpClass(cls):
        # 重试等待服务起来
        for attempt in range(30):
            try:
                cls.driver = GraphDatabase.driver(BOLT_URL, auth=AUTH)
                with cls.driver.session() as session:
                    session.run("RETURN 1 AS warmup")
                return
            except Exception as e:
                if attempt < 29:
                    time.sleep(0.5)
                    continue
                raise RuntimeError(
                    f"Failed to connect to z-graph BoltServer at {BOLT_URL}: {e}"
                )

    @classmethod
    def tearDownClass(cls):
        cls.driver.close()

    def _run_and_get_single(self, cypher: str):
        with self.driver.session() as session:
            result = session.run(cypher)
            record = result.single()
            return record

    def test_01_return_integer_alias(self):
        record = self._run_and_get_single("RETURN 1 AS n")
        self.assertIsNotNone(record)
        self.assertEqual(record["n"], 1)

    def test_02_return_string_alias(self):
        record = self._run_and_get_single('RETURN "hello" AS msg')
        self.assertIsNotNone(record)
        self.assertEqual(record["msg"], "hello")

    def test_03_return_multiple_columns(self):
        with self.driver.session() as session:
            result = session.run("RETURN 1 AS x, \"y\" AS y, true AS z")
            record = result.single()
            self.assertEqual(record["x"], 1)
            self.assertEqual(record["y"], "y")
            self.assertEqual(record["z"], True)

    def test_04_return_negative(self):
        record = self._run_and_get_single("RETURN -42 AS n")
        self.assertEqual(record["n"], -42)

    def test_05_return_no_alias(self):
        with self.driver.session() as session:
            result = session.run("RETURN 1, 2, 3")
            record = result.single()
            # neo4j python driver 默认把无别名映射成 "<literal>"
            self.assertIsNotNone(record)
            values = list(record.values())
            self.assertEqual(values, [1, 2, 3])

    def test_06_return_null(self):
        record = self._run_and_get_single("RETURN null AS x")
        self.assertIsNotNone(record)
        self.assertIsNone(record["x"])

    def test_07_return_boolean(self):
        record = self._run_and_get_single("RETURN false AS flag")
        self.assertIsNotNone(record)
        self.assertEqual(record["flag"], False)


def main():
    loader = unittest.TestLoader()
    suite = loader.loadTestsFromTestCase(BoltPocE2ETest)
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())