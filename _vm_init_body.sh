#!/busybox sh
mount -t devtmpfs dev /dev 2>/dev/null
mount -t proc proc /proc 2>/dev/null
echo "=== BOOT OK ==="
[ -f /lib/ld-musl-aarch64.so.1 ] || echo "!! 缺 loader"
# 解释器（Tier0）冒烟：PIE 在 flat 后端（arena=0）也必须能跑。
# 这是「原生通道失败时终端仍可用」这条兜底的唯一证据。
echo "--- tier0 smoke (CH_TIER=0)"
CH_TIER=0 /shell /busybox /jail 'echo tier0-ok | wc -c' 2>&1 | /busybox sed 's/^/    /'
export CH_EXEC_DEBUG=1
pass=0; fail=0
while IFS='	' read -r desc cmd; do
  [ -z "$desc" ] && continue
  printf '\n--- %s\n    $ %s\n' "$desc" "$cmd"
  /shell /busybox /jail "$cmd" 2>&1 | /busybox sed 's/^/    /'
done < /tests.txt
echo "=== DONE ==="
/busybox poweroff -f
