#!/usr/bin/env bash
set -e

echo "=== SFTP BiSync Rust Core UniFFI 빌드 스크립트 ==="

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

# 1. Desktop Host Build
echo "[1/3] 데스크톱 호스트 네이티브 라이브러리 빌드 중..."
cargo build --release

# 2. Android NDK Build (NDK 환경변수가 있을 때만 실행)
if [ -n "$ANDROID_NDK_HOME" ] || [ -n "$ANDROID_NDK_ROOT" ]; then
    echo "[2/3] Android NDK 타겟 빌드 중..."
    if command -v cargo-ndk &> /dev/null; then
        cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../composeApp/src/androidMain/jniLibs build --release
        echo "Android jniLibs 생성 완료!"
    else
        echo "cargo-ndk 가 설치되어 있지 않습니다. (설치: cargo install cargo-ndk)"
    fi
else
    echo "[2/3] ANDROID_NDK_HOME이 설정되지 않아 Android NDK 빌드를 건너뜁니다."
fi

# 3. UniFFI 바인딩 생성 가이드
echo "[3/3] 빌드 완료! 산출물 위치: $DIR/target/release/"
