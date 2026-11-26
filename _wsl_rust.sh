#!/bin/sh
# 在 Alpine WSL 里装 Rust 工具链。llvm19-libs 约 100MB、WSL1 解压极慢，
# 必须一次装完（中断会回滚数据库，下次从头再来）。
set -x
rm -f /lib/apk/db/lock
apk add --no-cache llvm19-libs
apk add --no-cache libxml2
apk add --no-cache rust
apk add --no-cache cargo
echo "=== toolchain ==="
cargo --version
rustc --version
echo "=== ok ==="
