#!/busybox sh
mount -t devtmpfs dev /dev 2>/dev/null
mount -t proc proc /proc 2>/dev/null
echo "=== BOOT OK ==="
[ -f /lib/ld-musl-aarch64.so.1 ] || echo "!! 缺 loader"
pass=0; fail=0
while IFS='	' read -r desc cmd; do
  [ -z "$desc" ] && continue
  printf '\n--- %s\n    $ %s\n' "$desc" "$cmd"
  /shell /busybox /jail "$cmd" 2>&1 | /busybox sed 's/^/    /'
done < /tests.txt
echo "########## 第二遍：带原生通道统计 ##########"
export CH_NATIVE_DEBUG=1
export CH_IDENTITY_DEBUG=1
while IFS='	' read -r desc cmd; do
  [ -z "$desc" ] && continue
  printf '
--- %s
' "$desc"
  /shell /busybox /jail "$cmd" 2>&1 | /busybox grep -E 'ch-native|ch-mem|回落' | /busybox head -3
done < /tests.txt
echo "=== DONE ==="
/busybox poweroff -f
