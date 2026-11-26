#!/bin/sh
# 构建 Android arm64-v8a 的 libchlinux.so，并落到 app/src/main/jniLibs/。
#
# 为什么要 wrapper：本机装的 NDK 只有 `prebuilt/windows-x86_64`（Android Studio
# 按宿主装），所以我们用 WSL 里的 clang，把 NDK 的 **sysroot**（头文件 + bionic
# 的 .so 存根，与平台无关）指过去，target 设成 aarch64-linux-android<API>。
set -e

PROJ="/mnt/d/Calw OS-project/ChengYan"
NDK="${NDK:-/mnt/d/Android/Sdk/ndk/27.0.12077973}"
API="${API:-24}"
SYSROOT="$NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot"
OUT="$PROJ/app/src/main/jniLibs/arm64-v8a/libchlinux.so"

export PATH=/root/.cargo/bin:$PATH

[ -d "$SYSROOT" ] || { echo "缺 NDK sysroot: $SYSROOT" >&2; exit 1; }
command -v clang >/dev/null || { echo "缺 clang（apk add clang lld）" >&2; exit 1; }

WRAP=/tmp/aarch64-android-clang
cat > "$WRAP" <<EOS
#!/bin/sh
exec clang --target=aarch64-linux-android$API --sysroot=$SYSROOT "\$@"
EOS
chmod +x "$WRAP"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$WRAP"
export CC_aarch64_linux_android="$WRAP"
export AR_aarch64_linux_android=llvm-ar

cd "$PROJ/linux-core"
cargo build --release -p ch-jnihost --target aarch64-linux-android

SO="target/aarch64-linux-android/release/libchlinux.so"
[ -f "$SO" ] || { echo "没产出 $SO" >&2; exit 1; }
cp "$SO" "$OUT"
echo "已更新 $OUT"
ls -l "$OUT"
file "$OUT" 2>/dev/null || true
