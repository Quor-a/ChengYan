@echo off
call "D:\VS2022\VC\Auxiliary\Build\vcvars64.bat" >nul
set "PATH=C:\Users\admin\.cargo\bin;%PATH%"
set "NDK=D:\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=%NDK%\aarch64-linux-android26-clang.cmd"
set "CC_aarch64_linux_android=%NDK%\aarch64-linux-android26-clang.cmd"
set "AR_aarch64_linux_android=%NDK%\llvm-ar.exe"
cd /d "%~dp0linux-core"
cargo build --release --target aarch64-linux-android -p ch-jnihost %*
if errorlevel 1 exit /b 1
echo [ok] libchlinux.so built
