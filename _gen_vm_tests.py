import re, io, os

src = open(r"D:\Calw OS-project\ChengYan\_e2e.sh", encoding="utf-8").read()
pairs = re.findall(r'^t\s+"((?:[^"\\]|\\.)*)"\s+"((?:[^"\\]|\\.)*)"', src, re.M)
def unesc(s):
    return s.replace('\\$', '$').replace('\\"', '"').replace('\\\\', '\\')
lines = []
for d, c in pairs:
    lines.append(unesc(d) + "\t" + unesc(c))
print("tests:", len(lines))
open(r"D:\Calw OS-project\ChengYan\_vm_tests.txt", "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")

init = """#!/busybox sh
mount -t devtmpfs dev /dev 2>/dev/null
mount -t proc proc /proc 2>/dev/null
echo "=== BOOT OK ==="
[ -f /lib/ld-musl-aarch64.so.1 ] || echo "!! 缺 loader"
pass=0; fail=0
while IFS='\t' read -r desc cmd; do
  [ -z "$desc" ] && continue
  printf '\\n--- %s\\n    $ %s\\n' "$desc" "$cmd"
  /shell /busybox /jail "$cmd" 2>&1 | /busybox sed 's/^/    /'
done < /tests.txt
echo "=== DONE ==="
/busybox poweroff -f
"""
open(r"D:\Calw OS-project\ChengYan\_vm_init_body.sh", "w", encoding="utf-8", newline="\n").write(init)
print("init written")
