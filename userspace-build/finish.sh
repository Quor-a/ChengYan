#!/bin/sh
# 组装 rootfs 并拷回项目（build-zig.sh 编译 busybox 成功后的收尾步骤）
set -eu
WORK=/tmp/cy-bb
OUT="$WORK/out"
SRC="/mnt/d/Calw OS-project/ChengYan/userspace-build"

rm -rf "$OUT"
for d in bin sbin lib etc usr/bin usr/sbin proc sys dev tmp root home; do mkdir -p "$OUT/$d"; done
cp "$WORK/busybox-1.37.0/busybox" "$OUT/bin/busybox"
chmod 755 "$OUT/bin/busybox"
cd "$OUT/bin"
for a in sh ash ls cat echo printf test true false mkdir rm cp mv ln wc grep sed awk sort head tail find chmod chown kill ps env cd pwd date sleep touch df du tar gzip base64 xxd seq vi; do
    ln -sf busybox "$a"
done
cd "$OUT/sbin"
for a in init halt reboot poweroff; do ln -sf ../bin/busybox "$a"; done
cd "$OUT/usr/bin"
for a in awk sed grep sh ash vi; do ln -sf ../../bin/busybox "$a"; done
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
tar --format=gnu -czf "$WORK/rootfs.tar.gz" .
cp "$WORK/rootfs.tar.gz" "$SRC/rootfs.tar.gz"
cp "$WORK/busybox-1.37.0/busybox" "$SRC/busybox-aarch64"
echo "DONE: $(wc -c < "$SRC/rootfs.tar.gz") bytes"
