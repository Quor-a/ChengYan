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
echo "=== DONE ==="
/busybox poweroff -f
