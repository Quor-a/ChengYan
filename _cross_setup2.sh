set -e
ps | grep "[r]ustup-init" | awk '{print $1}' | xargs -r kill -9 2>/dev/null || true
sleep 1
export RUSTUP_HOME=/root/.rustup CARGO_HOME=/root/.cargo
export RUSTUP_DIST_SERVER=https://rsproxy.cn
export RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup
echo "== 下载 rustup-init（rsproxy）"
curl -sSf -m 180 -o /tmp/rustup-init.bin https://rsproxy.cn/rustup/dist/x86_64-unknown-linux-musl/rustup-init
chmod +x /tmp/rustup-init.bin
echo "== 安装 toolchain 1.83.0"
/tmp/rustup-init.bin -y --no-modify-path --profile minimal --default-toolchain 1.83.0
export PATH=/root/.cargo/bin:$PATH
echo "== 版本"; rustc --version
echo "== 加 aarch64-unknown-linux-musl"
rustup target add aarch64-unknown-linux-musl
echo "== self-contained 内容"
ls /root/.rustup/toolchains/*/lib/rustlib/aarch64-unknown-linux-musl/lib/self-contained/ 2>/dev/null | head
echo "SETUP_DONE"
