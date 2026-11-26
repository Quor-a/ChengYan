#!/bin/sh
# 打包 initramfs（含最新 aarch64 shell）并启动 aarch64 VM
set -e
P="/mnt/d/Calw OS-project/ChengYan"
R=/tmp/cyvm/root
NEW="$P/linux-core/target/aarch64-unknown-linux-musl/release/examples/shell"
[ -f "$NEW" ] && cp "$NEW" $R/shell && echo "[vm] shell 已更新: $(ls -l "$NEW" | awk '{print $5}') bytes"
cp "$P/_vm_init_body.sh" $R/init
cp "$P/_vm_tests.txt" $R/tests.txt
chmod +x $R/init $R/shell
# 真 rootfs jail（/etc/passwd、/etc/os-release 等测试依赖），首次拷入
if [ ! -d $R/jail/etc ]; then
  echo "[vm] 拷入 jail ..."
  cp -a /tmp/cy/jail $R/jail
fi
cd $R && find . -print0 | cpio --null -o -H newc 2>/dev/null | gzip -9 > /tmp/cyvm/initrd.gz
timeout 300 qemu-system-aarch64 -M virt -cpu cortex-a72 -m 1024 -nographic -no-reboot \
  -kernel /tmp/cyvm/vmlinuz -initrd /tmp/cyvm/initrd.gz \
  -append "console=ttyAMA0 rdinit=/init quiet panic=-1" 2>&1
