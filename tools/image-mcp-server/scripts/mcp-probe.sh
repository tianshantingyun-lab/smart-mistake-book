#!/bin/bash
# MCP stdio 握手探针：验证 server 能 initialize + tools/list 列出工具。
# 用法：先 ./gradlew shadowJar 打包，再运行本脚本。
set -e
cd "$(dirname "$0")/.."
JAR=build/libs/image-mcp-server-0.1.0-all.jar
if [ ! -f "$JAR" ]; then
  echo "先运行 ./gradlew shadowJar 打包" >&2
  exit 1
fi
req1='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1.0"}}}'
req3='{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}'
{ printf '%s\n' "$req1"; sleep 1; printf '%s\n' "$req3"; sleep 2; } \
  | OPENAI_API_KEY="${OPENAI_API_KEY:-fake-for-probe}" java -jar "$JAR" 2>/dev/null \
  | grep -E '"tools"|serverInfo|error' || true
