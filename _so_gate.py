#!/usr/bin/env python3
"""APK 内 so 加载面门禁 —— 出包前必跑。

真机被这个坑打回过两次，都是 VM 全绿但 APK 起不来：

1. `DT_INIT_ARRAY` 注册了 Rust 构造函数 → dlopen 期间执行、Rust std 的
   TLS/stdio/env 不可靠 → `System.loadLibrary` 直接失败。
2. `#[link_name = "__sigsetjmp"]` 迁就 musl → bionic 只导出 `sigsetjmp` →
   `dlopen failed: cannot locate symbol "__sigsetjmp"`。

两次的表现都是 `NoClassDefFoundError: com.chengyan.app.data.LinuxNative`，
因为 `<clinit>` 挂了之后 ART 就把类标记成 erroneous（message 就是类名，
不是 "Could not initialize class" —— 那是 HotSpot 的格式，别被带偏）。

这个脚本用 NDK 的 bionic stub 库（API 21）当权威白名单，逐符号核对 UND，
任何一条不满足都会 FAIL。用法：

    python _so_gate.py [apk路径]

缺省检查 app/build/outputs/apk/release/app-release.apk。
"""
import glob
import os
import struct
import sys
import zipfile

NDK = "D:/Android/Sdk/ndk"
# 与 app/build.gradle.kts 的 minSdk 保持一致（当前 26）。用低于 minSdk 的 stub
# 核对会误报（如 libandroid.so 的 ASharedMemory_create 是 API 26 才有）。
PREFERRED_NDK = "27.0.12077973"   # 与打包进去的 libc++_shared.so 同源
API_LEVELS = ["26", "27", "28", "29"]

# 系统库：这些允许由设备提供，不必在 APK 内
SYS_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libstdc++.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so",
    "libOpenSLES.so", "libOpenMAXAL.so", "libjnigraphics.so", "libmediandk.so",
    "libnativewindow.so", "libsync.so", "libvulkan.so", "libcamera2ndk.so",
    "libneuralnetworks.so", "libaaudio.so", "libamidi.so",
}

# 官方产物自带 .init_array（纯 C，安全）。只有我们自己编的 so 才禁。
INIT_ARRAY_ALLOWED = {"libc++_shared.so", "libdatastore_shared_counter.so"}

# glibc / musl 专属、bionic 没有的符号 —— 出现在 UND 里必挂
POISON = {
    "__sigsetjmp", "__longjmp_chk", "__isoc99_sscanf", "__isoc99_fscanf",
    "__xstat", "__fxstat", "__lxstat", "__xstat64", "__fxstat64", "__lxstat64",
    "__gmon_start__", "__libc_start_main", "__errno_location",
    "__stack_chk_fail_local", "_dl_find_object", "__res_init",
}


def find_ndk_lib_dir():
    pref = os.path.join(NDK, PREFERRED_NDK)
    ordered = ([pref] if os.path.isdir(pref) else []) +               sorted(glob.glob(os.path.join(NDK, "*")), reverse=True)
    for ver in ordered:
        pre = os.path.join(ver, "toolchains", "llvm", "prebuilt")
        for host in glob.glob(pre + "/*"):
            for api in API_LEVELS:
                d = os.path.join(host, "sysroot", "usr", "lib", "aarch64-linux-android", api)
                if os.path.isdir(d):
                    return d
    return None


def elf_facts(d):
    """返回 {needed, init_array, und, exp}；非动态 ELF 返回 None。"""
    if d[:4] != b"\x7fELF":
        return None
    e_phoff = struct.unpack_from("<Q", d, 32)[0]
    e_phnum = struct.unpack_from("<H", d, 56)[0]
    pt = {}
    for i in range(e_phnum):
        o = e_phoff + i * 56
        t = struct.unpack_from("<I", d, o)[0]
        off = struct.unpack_from("<Q", d, o + 8)[0]
        va = struct.unpack_from("<Q", d, o + 16)[0]
        fsz = struct.unpack_from("<Q", d, o + 32)[0]
        pt.setdefault(t, []).append((off, va, fsz))
    if 2 not in pt:
        return None
    dyn_off, dyn_sz = pt[2][0][0], pt[2][0][2]

    g = {}
    i = 0
    while i < dyn_sz:
        tag = struct.unpack_from("<q", d, dyn_off + i)[0]
        val = struct.unpack_from("<Q", d, dyn_off + i + 8)[0]
        if tag == 0:
            break
        g.setdefault(tag, []).append(val)
        i += 16

    def va2off(va):
        for (off, v, sz) in pt[1]:
            if v <= va < v + sz:
                return off + (va - v)
        return None

    strtab_off = va2off(g[5][0])

    def rd(o):
        e = d.index(b"\x00", o)
        return d[o:e].decode("latin1")

    needed = [rd(strtab_off + v) for v in g.get(1, [])]
    init_array = g.get(25)

    und, exp = set(), set()
    if 6 in g and 0x6FFFFEF5 in g:
        symtab_off = va2off(g[6][0])
        gh = va2off(g[0x6FFFFEF5][0])
        nb, symoff, bsz, _ = struct.unpack_from("<4I", d, gh)
        buckets_off = gh + 16 + bsz * 8
        chains_off = buckets_off + nb * 4
        mx = symoff - 1
        for k in range(nb):
            b = struct.unpack_from("<I", d, buckets_off + 4 * k)[0]
            if b < symoff:
                continue
            while True:
                h = struct.unpack_from("<I", d, chains_off + (b - symoff) * 4)[0]
                mx = max(mx, b)
                if h & 1:
                    break
                b += 1
        for k in range(mx + 1):
            o = symtab_off + k * 24
            nm, info, _other, shndx = struct.unpack_from("<IBBH", d, o)
            if nm == 0 or (info >> 4) not in (1, 2):
                continue
            name = rd(strtab_off + nm)
            (und if shndx == 0 else exp).add(name)
    return {"needed": needed, "init_array": init_array, "und": und, "exp": exp}


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"
    if not os.path.isfile(apk):
        print("找不到 APK:", apk)
        return 2

    ndk_dir = find_ndk_lib_dir()
    if not ndk_dir:
        print("找不到 NDK 的 aarch64 stub 目录，无法做符号白名单核对")
        return 2
    print("bionic 白名单:", ndk_dir)

    # 收集系统库导出符号
    sys_exp = set()
    for f in glob.glob(os.path.join(ndk_dir, "*.so")):
        try:
            r = elf_facts(open(f, "rb").read())
        except Exception:
            continue
        if r:
            sys_exp |= r["exp"]
    sys_exp |= {"__sF", "__errno", "__cxa_atexit", "__cxa_finalize", "__register_atfork", "environ"}
    print("系统库导出符号 %d 个\n" % len(sys_exp))

    z = zipfile.ZipFile(apk)
    arm64 = [n for n in z.namelist() if n.endswith(".so") and "/arm64-v8a/" in n]
    in_apk = {n.split("/")[-1] for n in arm64}

    fails = []
    for n in sorted(arm64):
        base = n.split("/")[-1]
        r = elf_facts(z.read(n))
        if r is None:
            print("[%s] 静态 / 无动态段，跳过" % base)
            continue
        print("[%s]" % base)

        miss_dep = [x for x in r["needed"] if x not in in_apk and x not in SYS_LIBS]
        if miss_dep:
            print("   ✗ 依赖缺失: %s" % ", ".join(miss_dep))
            fails.append("%s 依赖缺失 %s" % (base, miss_dep))
        else:
            print("   ✓ NEEDED 全部可解析 (%d)" % len(r["needed"]))

        if r["init_array"] and base not in INIT_ARRAY_ALLOWED:
            print("   ✗ DT_INIT_ARRAY 有 %d 项（我们的 so 禁止挂构造函数）" % len(r["init_array"]))
            fails.append("%s 有 DT_INIT_ARRAY" % base)
        elif r["init_array"]:
            print("   · DT_INIT_ARRAY %d 项（NDK 官方产物，允许）" % len(r["init_array"]))
        else:
            print("   ✓ 无 DT_INIT_ARRAY")

        poison = sorted(r["und"] & POISON)
        unresolved = sorted(s for s in r["und"] if s not in sys_exp and not s.startswith("_Z"))
        if poison:
            print("   ✗ 含 bionic 没有的符号: %s" % ", ".join(poison))
            fails.append("%s 含毒符号 %s" % (base, poison))
        if unresolved:
            print("   ✗ 白名单查不到的 UND: %s" % ", ".join(unresolved[:12]))
            fails.append("%s 未解析符号 %s" % (base, unresolved[:12]))
        if not poison and not unresolved:
            print("   ✓ UND %d 个全部在 bionic 内" % len(r["und"]))

        if base == "libchlinux.so":
            need = [s for s in ("JNI_OnLoad",) if s in r["exp"]]
            java = sorted(s for s in r["exp"] if s.startswith("Java_"))
            if not need:
                print("   ✗ 缺 JNI_OnLoad 导出")
                fails.append("libchlinux.so 缺 JNI_OnLoad")
            elif not java:
                print("   ✗ 缺 Java_* 导出")
                fails.append("libchlinux.so 缺 Java_* 导出")
            else:
                print("   ✓ 导出 JNI_OnLoad + %d 个 Java_*" % len(java))
        print()

    if fails:
        print("=" * 60)
        print("门禁 FAIL（%d 项）：" % len(fails))
        for f in fails:
            print("  -", f)
        return 1
    print("=" * 60)
    print("门禁 PASS：所有 arm64 so 可加载")
    return 0


if __name__ == "__main__":
    sys.exit(main())
