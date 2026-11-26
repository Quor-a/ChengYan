#!/bin/sh
# busybox kbuild 传 -Wp,-MD,<file>（逗号形式）生成依赖文件。
# zig cc 对该形式会把 .d 写进自己的缓存临时目录，随后报 FileNotFound。
# 本包装把 -Wp,-MD,<file> 改写成 -MD -MF <file>（等价且 zig 正确处理）。
args=""
while [ $# -gt 0 ]; do
    case "$1" in
        -Wp,-MD,*)  args="$args -MD -MF '${1#-Wp,-MD,}'"; shift ;;
        -Wp,-MMD,*) args="$args -MMD -MF '${1#-Wp,-MMD,}'"; shift ;;
        -Wl,--warn-common|-Wl,--sort-common|-Wl,--sort-section,*|-Wl,--verbose) shift ;;
        -Wl,-Map,*) shift ;;
        *) args="$args '$1'"; shift ;;
    esac
done
eval exec zig cc -target aarch64-linux-musl $args
