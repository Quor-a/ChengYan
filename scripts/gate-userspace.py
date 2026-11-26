#!/usr/bin/env python3
"""构建后门禁：验证 APK 内静态 busybox + 真 PTY 运行时完整。

当前架构（不用 proot）：
  lib/arm64-v8a/libpty.so        —— JNI forkpty（真 PTY）
  lib/arm64-v8a/libcybusybox.so  —— 自编译静态 busybox（aarch64 ELF）
  assets/cy-rootfs.tar           —— 自编译用户空间目录树

用法：python scripts/gate-userspace.py [apk路径]
"""
import io
import gzip
import struct
import sys
import tarfile
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"
fails = []

def check(cond, msg):
    print(("  OK   " if cond else "  FAIL ") + msg)
    if not cond:
        fails.append(msg)

print("=== APK 用户空间门禁（静态 busybox + 真 PTY）===")
with zipfile.ZipFile(APK) as z:
    names = set(z.namelist())

    print("[运行时 so]")
    check("lib/arm64-v8a/libpty.so" in names, "libpty.so（JNI forkpty）")
    check("lib/arm64-v8a/libcybusybox.so" in names, "libcybusybox.so（静态 busybox）")
    for so in ("libpty.so", "libcybusybox.so"):
        k = "lib/arm64-v8a/" + so
        if k in names:
            h = z.read(k)[:20]
            check(len(h) >= 20 and struct.unpack_from("<H", h, 18)[0] == 0xB7,
                  f"{so} 是 aarch64 ELF")

    print("[用户空间资产]")
    rootfs_name = next((n for n in names if n.startswith("assets/cy-rootfs.tar")), None)
    check(rootfs_name is not None, "assets/cy-rootfs.tar(.gz) 存在")

    if rootfs_name:
        print("[rootfs 内容/权限]")
        data = z.read(rootfs_name)
        raw = io.BytesIO(gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data)
        with tarfile.open(fileobj=raw, mode="r:") as t:
            members = {m.name.lstrip("./"): m for m in t.getmembers()}
            bb = members.get("bin/busybox")
            check(bb is not None, "bin/busybox 存在")
            if bb:
                check(bool(bb.mode & 0o111), "bin/busybox 有执行位 (mode=%o)" % bb.mode)
                f = t.extractfile(bb)
                hdr = f.read(20) if f else b""
                check(hdr[:4] == b"\x7fELF", "bin/busybox 是 ELF")
                if len(hdr) >= 20:
                    check(struct.unpack_from("<H", hdr, 18)[0] == 0xB7, "bin/busybox 是 aarch64")
            sh = members.get("bin/sh")
            check(sh is not None and sh.issym() and sh.linkname == "busybox", "bin/sh → busybox 软链")
            check("etc/os-release" in members, "etc/os-release 存在")

    print("[旧内核残留]")
    check("lib/arm64-v8a/libchlinux.so" not in names, "libchlinux.so 已移除")

print("")
if fails:
    print("门禁失败 %d 项，禁止交付：" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("门禁 PASS")
