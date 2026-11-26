#!/bin/sh
# 引擎真值测试矩阵：在 Linux 宿主上跑真实 Alpine aarch64 busybox，验证终端可用性。
# 用法：wsl -d molang -e sh "/mnt/d/Calw OS-project/ChengYan/_e2e.sh"
set -u
PROJ="/mnt/d/Calw OS-project/ChengYan"
CORE="$PROJ/linux-core"
WORK=/tmp/cy
BB="$WORK/bb"
JAIL="$WORK/jail"

mkdir -p "$WORK"
[ -f "$BB" ] || cp "$PROJ/app/src/main/assets/busybox" "$BB"
if [ ! -f "$JAIL/bin/busybox.static" ]; then
  mkdir -p "$JAIL"
  tar xzf "$PROJ/app/src/main/assets/rootfs.tar.gz" -C "$JAIL" 2>/dev/null
  # 复现 Rootfs.kt 的补丁：/bin/busybox 缺失 → 334 个 applet 悬空
  [ -f "$JAIL/bin/busybox" ] || cp "$JAIL/bin/busybox.static" "$JAIL/bin/busybox"
  chmod +x "$JAIL/bin/busybox" 2>/dev/null
fi
echo "=== 环境 ==="
echo "busybox: $(ls -l "$BB" | awk '{print $5}') bytes  magic=$(od -An -c -N4 "$BB" | tr -d ' \n')  machine=$(od -An -tx1 -j18 -N2 "$BB" | tr -d ' \n')"
echo "rootfs : $(find "$JAIL" -maxdepth 1 | wc -l) top entries"

# 可切到别的构建产物：CH_RUN 指定二进制，CH_QEMU 指定执行器（aarch64 交叉产物用）。
RUN="${CH_RUN:-$CORE/target/release/examples/shell}"

run_guest() {
  if [ -n "${CH_QEMU:-}" ]; then "$CH_QEMU" "$RUN" "$@"; else "$RUN" "$@"; fi
}

t() {
  desc="$1"; cmd="$2"
  printf '\n--- %s\n    $ %s\n' "$desc" "$cmd"
  run_guest "$BB" "$JAIL" "$cmd" 2>&1 | sed 's/^/    /'
}

echo
echo "############ 基础 applet ############"
t "echo"                 "echo hello-chengyan"
t "ls 根"                "ls /"
t "cat /etc/os-release"  "cat /etc/os-release"
t "再跑一次 cat（会话可复用）" "cat /etc/alpine-release"

echo
echo "############ cd / 相对路径 ############"
t "cd 到 /etc 后 pwd"    "cd /etc; pwd"
t "cd 后相对 ls"         "cd /etc; ls os-release"
t "cd .. 上跳"           "cd /etc/apk; cd ..; pwd"
t "cd 不存在目录"        "cd /nope"
t "绝对路径不受 cd 影响"  "cd /etc; cat /etc/alpine-release"

echo
echo "############ 管道 ############"
t "ls | wc -l"           "ls /etc | wc -l"
t "grep | cut | head"    "grep root /etc/passwd | cut -d: -f1 | head -3"
t "三段管道"             "cat /etc/passwd | grep -c . "

echo
echo "############ 重定向 ############"
t "> 写入"               "echo abc > /tmp/t1.txt; cat /tmp/t1.txt"
t ">> 追加"              "echo abc > /tmp/t2.txt; echo def >> /tmp/t2.txt; cat /tmp/t2.txt"
t "< 读取"               "echo xyz > /tmp/t3.txt; cat < /tmp/t3.txt"
t "2>&1 合并"            "ls /definitely-not-here 2>&1 | head -2"
t "重定向到相对路径"      "cd /tmp; echo rel > rel.txt; cat /tmp/rel.txt"

echo
echo "############ 变量 / 内建 ############"
t "export + 展开"        "export FOO=bar42; echo \$FOO"
t "echo \$HOME"          "echo \$HOME"
t "echo \$PWD"           "echo \$PWD"
t "\$? 退出码"           "false; echo code=\$?"
t "\${VAR} 形式"         "export A=1; echo \${A}end"
t "unset"                "export B=9; unset B; echo [\$B]"

echo
echo "############ glob ############"
t "*.txt 展开"           "cd /tmp; touch g1.txt g2.txt g3.log; ls *.txt"
t "? 单字符"             "cd /tmp; ls g?.log"
t "无匹配保持原样"        "cd /tmp; echo nope*.zzz"

echo
echo "############ 逻辑组合 ############"
t "&& 成功链"            "true && echo ok1"
t "&& 短路"              "false && echo never"
t "|| 兜底"              "false || echo fallback"
t "; 顺序"               "echo a; echo b; echo c"

echo
echo "############ 常用工具链 ############"
t "mkdir -p + cd"        "mkdir -p /tmp/d1/d2; cd /tmp/d1/d2; pwd"
t "awk 小数"             "awk 'BEGIN{printf \"%.2f\\n\", 1.5+2.25}'"
t "sed 替换"             "echo aaa | sed 's/a/b/g'"
t "sort | uniq"          "printf 'b\\na\\nb\\n' | sort | uniq"
t "sort | head"          "printf '3\\n1\\n2\\n' | sort | head -2"
t "head 截断（lseek 回退）" "printf '1\\n2\\n3\\n4\\n5\\n' | head -2"
t "tail 取尾"            "printf '1\\n2\\n3\\n4\\n5\\n' | tail -2"
t "管道中间段不报 EBADF"  "printf 'b\\na\\nb\\n' | sort | uniq 2>&1 | wc -l"
t "find"                 "find /etc -maxdepth 1 -name 'os*'"
t "cp / mv / rm"         "echo z > /tmp/m1; cp /tmp/m1 /tmp/m2; mv /tmp/m2 /tmp/m3; cat /tmp/m3; rm -f /tmp/m3; ls /tmp/m3 2>&1 | head -1"
t "wc -l 多文件"          "wc -l /etc/passwd /etc/group"

echo
echo "############ 无界生产者（SIGPIPE 上限） ############"
t "yes|head 不挂死"      "yes ch | head -3"
t "无界流按字节截断"     "yes abc | head -c 16 | wc -c"
t "yes|wc 也被截断"      "yes ch | head -5 | wc -l"
t "重复输出不炸内存"      "yes abc | tail -1 | head -1"

echo
echo "############ 越狱防护 ############"
t "../.. 不出 jail"      "cd /; ls ../../ 2>&1 | head -3"
t "读宿主 /etc/shadow"    "cat ../../../etc/shadow 2>&1 | head -2"

echo
echo "=== 完成 ==="
