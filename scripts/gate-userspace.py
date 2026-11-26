#!/usr/bin/env python3
"""构建后门禁：验证 APK 内自编译用户空间 + proot 依赖链完整。

背景：连续多轮出现「修 A 挂 B」——talloc 缺失、busybox 无执行位等，
都是打包产物层面的问题，真机测一次才发现。本门禁在 assembleRelease 之后
直接检查 APK 内容，任何一项失败就非零退出，阻止交付。

用法：python scripts/gate-userspace.py [apk路径]
"""
import io
import struct
import sys
import tarfile
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"

fails = []
warns = []

def check(cond, msg):
    if cond:
        print("  OK  " + msg)
    else:
        print("  FAIL " + msg)
        fails.append(msg)

print("=== APK 用户空间门禁 ===")
with zipfile.ZipFile(APK) as z:
    names = set(z.namelist())

    # 1) proot 与依赖链（nativeLibraryDir 有 exec 权限）
    print("[proot 依赖链]")
    for so in ["lib/arm64-v8a/libproot.so",
               "lib/arm64-v8a/liblibtalloc.so.2.so",
               # filesDir noexec：busybox 必须走 nativeLibraryDir 才能 execve
               "lib/arm64-v8a/libcybusybox.so",
               "lib/arm64-v8a/libc++_shared.so"]:
        check(so in names, so)
    if "lib/arm64-v8a/libcybusybox.so" in names:
        h = z.read("lib/arm64-v8a/libcybusybox.so")[:20]
        check(len(h) >= 20 and struct.unpack_from("<H", h, 18)[0] == 0xB7,
              "libcybusybox.so 是 aarch64 ELF")

    # 2) 自编译用户空间资产
    print("[用户空间资产]")
    rootfs_name = next((n for n in names if n.startswith("assets/cy-rootfs.tar")), None)
    check(rootfs_name is not None, "assets/cy-rootfs.tar(.gz) 存在")
    check("assets/linux_env/libproot-loader.so" in names, "proot loader 存在")

    # 3) rootfs 关键文件 + 执行位（真机 /bin/sh not executable 的直接门禁）
    if rootfs_name:
        print("[rootfs 内容/权限]")
        data = z.read(rootfs_name)
        # AGP 可能解压 .gz 存成 .tar，按魔数判断
        raw = io.BytesIO(data)
        if data[:2] == b"\x1f\x8b":
            import gzip
            raw = io.BytesIO(gzip.decompress(data))
        with tarfile.open(fileobj=raw, mode="r:") as t:
            members = {m.name.lstrip("./"): m for m in t.getmembers()}
            bb = members.get("bin/busybox")
            check(bb is not None, "bin/busybox 存在")
            if bb:
                check(bool(bb.mode & 0o111), "bin/busybox 有执行位 (mode=%o)" % bb.mode)
                # aarch64 ELF machine=0xb7
                f = t.extractfile(bb)
                hdr = f.read(20) if f else b""
                check(hdr[:4] == b"\x7fELF", "bin/busybox 是 ELF")
                if len(hdr) >= 20:
                    machine = struct.unpack_from("<H", hdr, 18)[0]
                    check(machine == 0xB7, "bin/busybox 是 aarch64 (machine=0xb7, got 0x%x)" % machine)
            sh = members.get("bin/sh")
            check(sh is not None and sh.issym() and sh.linkname == "busybox",
                  "bin/sh 是指向 busybox 的软链")
            check("etc/os-release" in members, "etc/os-release 存在")
            check("etc/passwd" in members, "etc/passwd 存在")

    # 4) 旧内核已移除
    print("[旧内核残留]")
    check("lib/arm64-v8a/libchlinux.so" not in names, "libchlinux.so 已移除")

print("")
if fails:
    print("门禁失败 %d 项，禁止交付：" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("门禁 PASS")
