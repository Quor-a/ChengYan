set -x
apk add --no-cache qemu-system-aarch64 2>&1 | tail -5
which qemu-system-aarch64
qemu-system-aarch64 --version | head -2
echo "== 下载 aarch64 内核"
mkdir -p /tmp/a64kern
apk fetch --arch aarch64 -R -o /tmp/a64kern linux-virt 2>&1 | tail -3
ls -l /tmp/a64kern
echo VM_SETUP_DONE
