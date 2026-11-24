#!/bin/sh
# FD-OS S0 探针构建：生成嵌入 blob + NDK 编译 libfdosprobe.so → jniLibs
set -e
cd "$(dirname "$0")"
NDK=/home/agentuser/android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64/bin
CLANG=$NDK/aarch64-linux-android26-clang
CLANGXX=$NDK/aarch64-linux-android26-clang++

# 1) 退出码 42 的最小静态 ELF
$CLANG -nostdlib -static -Wl,-e,_start -o /tmp/fdos_probe42 probe42.S
# 2) dlopen 用最小 .so
$CLANG -shared -fPIC -o /tmp/fdos_dummy.so fdosdummy.c
# 3) 转 C 数组（无 xxd 用 python）
python3 - <<'PY'
def dump(src, sym):
    data = open(src, 'rb').read()
    with open(sym + '.h', 'w') as f:
        f.write(f'unsigned char {sym}[] = {{\n')
        for i in range(0, len(data), 12):
            f.write('  ' + ','.join(str(b) for b in data[i:i+12]) + ',\n')
        f.write('};\n')
        f.write(f'unsigned int {sym}_len = {len(data)};\n')
dump('/tmp/fdos_probe42', 'fdos_probe42_blob')
dump('/tmp/fdos_dummy.so', 'fdos_dummy_blob')
PY
sed -i 's|#include "probe42_inc.h".*|#include "fdos_probe42_blob.h"  // fdos_probe42_blob / _len|; s|#include "probe_so_inc.h".*|#include "fdos_dummy_blob.h"   // fdos_dummy_blob / _len|' fdosprobe.cpp

# 4) 探针主库
$CLANGXX -O2 -fPIC -shared -Wno-writable-strings -o libfdosprobe.so \
  fdosprobe.cpp -Wl,--no-undefined -ldl -landroid -llog

cp libfdosprobe.so ../../app/src/main/jniLibs/arm64-v8a/
echo BUILD-OK
