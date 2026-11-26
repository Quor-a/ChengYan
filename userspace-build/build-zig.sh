#!/bin/sh
# 从源码交叉编译 ChengYan 自己的 aarch64 Linux 用户空间。
# 用 zig 0.13 自带的 aarch64-linux-musl libc（不复制任何发行版 rootfs）。
# 在 WSL 执行：wsl bash -c 'sh "/mnt/d/Calw OS-project/ChengYan/userspace-build/build-zig.sh"'
set -eu
PROJ="/mnt/d/Calw OS-project/ChengYan"
SRC="$PROJ/userspace-build"
WORK=/tmp/cy-bb          # busybox make 不能在带空格路径工作
BB=busybox-1.37.0
ZIG=zig

echo "[1/5] 准备源码"
rm -rf "$WORK"
mkdir -p "$WORK"
cp -a "$SRC/$BB" "$WORK/"
cd "$WORK/$BB"
# patch fixdep：zig cc 把 depfile 写进缓存临时目录，fixdep 打不开就报错；
# 依赖追踪只是增量优化，缺文件时直接成功退出不影响产物。
python3 - <<'PYEOF'
p="scripts/basic/fixdep.c"
s=open(p).read()
anchor="int main(int argc, char **argv)\n{\n\ttraps();\n"
if "access(argv[1], F_OK)" not in s and anchor in s:
    s=s.replace(anchor, anchor+"\tif (argc == 4 && access(argv[1], F_OK) != 0) return 0;\n", 1)
    open(p,"w").write(s)
PYEOF
make distclean >/dev/null 2>&1 || true
make defconfig >/dev/null
sed -i 's/^# CONFIG_STATIC is not set/CONFIG_STATIC=y/' .config
sed -i 's/^# CONFIG_FEATURE_SH_STANDALONE is not set/CONFIG_FEATURE_SH_STANDALONE=y/' .config
# SHA1/SHA256 HWACCEL 是 x86 SHA-NI 扩展，aarch64 上符号未定义 → 必须关闭
sed -i 's/^CONFIG_SHA1_HWACCEL=y/# CONFIG_SHA1_HWACCEL is not set/' .config
sed -i 's/^CONFIG_SHA256_HWACCEL=y/# CONFIG_SHA256_HWACCEL is not set/' .config

echo "[2/5] 交叉编译 busybox（zig cc → aarch64-linux-musl）"
# zig cc 是 gcc/clang 的 drop-in 替代品，直接用 CROSS_COMPILE 指向包装脚本
cp "$SRC/zig-cc-wrap.sh" "$WORK/aarch64-linux-musl-cc"
chmod +x "$WORK/aarch64-linux-musl-cc"
# strip：用 Alpine 的 aarch64 strip（busybox Makefile 会调 $(CROSS_COMPILE)strip）
cat > "$WORK/aarch64-linux-musl-strip" <<'EOF'
#!/bin/sh
exec aarch64-none-elf-strip "$@"
EOF
chmod +x "$WORK/aarch64-linux-musl-strip"
make -j4 \
    CROSS_COMPILE="$WORK/aarch64-linux-musl-" \
    CC="$WORK/aarch64-linux-musl-cc" \
    LD="$WORK/aarch64-linux-musl-cc" \
    AR="zig ar" \
    CFLAGS="-Os -fno-stack-protector -Wno-error" \
    >/tmp/cy-bb-build.log 2>&1 || {
    echo "编译失败，日志尾部："; tail -40 /tmp/cy-bb-build.log; exit 1;
}
[ -f busybox ] || { echo "busybox 未生成" >&2; exit 1; }
python3 -c "
b=open('busybox','rb').read()
print('busybox:', b[:4], 'machine=', hex(int.from_bytes(b[18:20],'little')), len(b),'bytes')
assert b[:4]==b'\x7fELF' and int.from_bytes(b[18:20],'little')==0xb7"

echo "[3/5] 组装 rootfs 目录树"
OUT="$WORK/out"
rm -rf "$OUT"
for d in bin sbin lib etc usr/bin usr/sbin proc sys dev tmp root home; do mkdir -p "$OUT/$d"; done
cp busybox "$OUT/bin/busybox"
chmod 755 "$OUT/bin/busybox"
cd "$OUT/bin"
# 静态 busybox：aarch64 二进制无法在 host 跑 --list，用固定 applet 列表
for a in sh ash ls cat echo printf test true false mkdir rm cp mv ln wc grep sed awk sort head tail find chmod chown kill ps env cd pwd date sleep touch df du tar gzip base64 xxd seq vi; do
    ln -sf busybox "$a"
done
cd "$OUT/sbin"
for a in init halt reboot poweroff; do ln -sf ../bin/busybox "$a"; done
cd "$OUT/usr/bin"
for a in awk sed grep sh ash vi; do ln -sf ../../bin/busybox "$a"; done

echo "[4/5] 自己写配置文件（不拷贝任何发行版）"
cd "$OUT"
cat > etc/os-release <<'EOF'
NAME="ChengYan Userspace"
ID=chengyan
VERSION_ID=1.0
PRETTY_NAME="ChengYan cross-compiled aarch64 userspace"
EOF
printf 'root:x:0:0:root:/root:/bin/sh\n' > etc/passwd
printf 'root:x:0:\n' > etc/group
printf '127.0.0.1\tlocalhost\n' > etc/hosts
cat > etc/profile <<'EOF'
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
PS1='$ '
EOF

echo "[5/5] 打包 rootfs.tar.gz 并拷回项目"
cd "$OUT"
tar --format=gnu -czf "$WORK/rootfs.tar.gz" .
cp "$WORK/rootfs.tar.gz" "$SRC/rootfs.tar.gz"
cp busybox "$SRC/busybox-aarch64"
echo "完成: $SRC/rootfs.tar.gz ($(wc -c < "$SRC/rootfs.tar.gz") 字节)"
