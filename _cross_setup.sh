set -e
export RUSTUP_HOME=/root/.rustup CARGO_HOME=/root/.cargo
if [ ! -x /root/.cargo/bin/rustup ]; then
  echo "== 装 rustup"
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs -o /tmp/rustup-init.sh
  sh /tmp/rustup-init.sh -y --no-modify-path --profile minimal --default-toolchain 1.83.0
fi
export PATH=/root/.cargo/bin:$PATH
echo "== 版本"; rustc --version
echo "== 加 aarch64 target"
rustup target add aarch64-unknown-linux-musl
echo "== rustlib 内容"
ls /root/.rustup/toolchains/*/lib/rustlib/aarch64-unknown-linux-musl/lib/ | head -20
echo "== self-contained"
ls /root/.rustup/toolchains/*/lib/rustlib/aarch64-unknown-linux-musl/lib/self-contained/ 2>/dev/null | head -20
echo "SETUP_DONE"
