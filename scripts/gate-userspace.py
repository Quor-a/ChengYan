#!/usr/bin/env python3
"""构建后门禁：验证 APK 内含 ZorvAI 完整终端运行时资产。失败即非零退出。"""
import sys, zipfile, struct, os

APK = os.path.join(os.path.dirname(__file__), "..", "app", "build", "outputs", "apk", "release", "app-release.apk")
APK = os.path.normpath(APK)
fails = []
def check(cond, what):
    print(("  OK   " if cond else "  FAIL ") + what)
    if not cond: fails.append(what)

def is_aarch64_elf(data):
    return len(data) > 20 and data[:4] == b"\x7fELF" and data[4] == 2 and struct.unpack_from("<H", data, 18)[0] == 0xB7

print(f"门禁: {APK}")
if not os.path.exists(APK):
    print("FAIL APK 不存在"); sys.exit(1)

with zipfile.ZipFile(APK) as z:
    names = set(z.namelist())
    print("=== 1. 完整工具链 (nativeLibraryDir) ===")
    for so in ["lib/arm64-v8a/libbash.so", "lib/arm64-v8a/libbusybox.so",
               "lib/arm64-v8a/libproot.so", "lib/arm64-v8a/libloader.so",
               "lib/arm64-v8a/liblibtalloc.so.2.so",
               "lib/arm64-v8a/libpty.so"]:
        check(so in names, so)
        if so in names:
            check(is_aarch64_elf(z.read(so)), so + " 是 aarch64 ELF")

    print("=== 2. Alpine 3.24 rootfs 资产 ===")
    rootfs = next((n for n in names if "alpine-minirootfs-3.24.0" in n), None)
    check(rootfs is not None, "Alpine 3.24 rootfs 资产存在")
    if rootfs:
        sz = z.getinfo(rootfs).file_size
        check(3_000_000 < sz < 12_000_000, f"Alpine rootfs 大小 {sz} 在 3-12MB")

    print("=== 3. proot loader ===")
    check("assets/linux_env/libproot-loader.so" in names, "libproot-loader.so")

    print("=== 4. 旧自研内核已移除 ===")
    check(not any("libchlinux" in n for n in names), "libchlinux.so 不存在")

print("=== 结果 ===")
if fails:
    print(f"FAIL {len(fails)} 项未通过"); sys.exit(1)
print("PASS"); sys.exit(0)
