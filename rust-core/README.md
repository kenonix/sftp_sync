# SFTP BiSync Rust Core (Hybrid UniFFI Architecture)

이 디렉터리는 **SFTP BiSync** 애플리케이션의 핵심 비즈니스 로직(양방향 동기화 판정, 초고속 SHA-256 해시 계산, 토큰 버킷 대역폭 제한)을 Rust로 분리하여 네이티브 성능과 안전성을 극대화하기 위한 모듈입니다.

---

## 🏛️ 아키텍처 개요

```
┌────────────────────────────────────────────────────────┐
│            Android / Desktop UI & OS Layer             │
│   - Jetpack Compose (Material 3 선언형 UI)             │
│   - SyncForegroundService (안드로이드 백그라운드 상주) │
│   - Storage Access Framework (SAF) 및 권한 관리        │
└───────────────────────────▲────────────────────────────┘
                            │ JNI / UniFFI Kotlin Bindings
┌───────────────────────────▼────────────────────────────┐
│                    Rust Core Engine                    │
│   - engine.rs    : 3-Way Diff 및 충돌 판정 엔진        │
│   - bandwidth.rs : 정밀 토큰 버킷 네트워크 속도 제한기  │
│   - hasher.rs    : 16KB 청크 기반 초고속 SHA-256 해시  │
│   - errors.rs    : UniFFI 에러 타입 및 매핑             │
└────────────────────────────────────────────────────────┘
```

---

## 📦 모듈 구성

- **`bandwidth.rs`**: 원자적 변수(`AtomicBool`, `AtomicI64`)와 고정밀 `Instant`를 사용한 토큰 버킷 알고리즘 구현. 병렬 SFTP 전송에서도 락 오버헤드 없이 합산 대역폭을 정밀하게 제어합니다.
- **`hasher.rs`**: `sha2::Sha256`를 기반으로 64KB 버퍼 스트리밍을 통해 수 GB 이상의 대용량 파일도 JVM GC 부담 없이 초고속으로 해시를 계산합니다.
- **`engine.rs`**: 로컬 파일 트리, 원격 파일 트리, 이전 동기화 메타데이터를 3-Way 비교하여 UPLOAD, DOWNLOAD, CONFLICT 등의 액션을 O(N)으로 고속 연산합니다.
- **`lib.rs`**: `uniffi::setup_scaffolding!()`을 통해 Android 및 Desktop 타겟에서 FFI 심볼을 자동으로 노출합니다.

---

## 🛠️ 빌드 및 연동 가이드

### 1. Android NDK 크로스 컴파일 (JNI 라이브러리 생성)

`cargo-ndk` 도구를 사용하여 Android ABI별 `.so` 파일을 생성하고 안드로이드 소스 세트에 복사합니다.

```bash
# 1. cargo-ndk 설치
cargo install cargo-ndk

# 2. Android 타겟 Rust 툴체인 추가
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# 3. Android jniLibs 디렉터리로 컴파일 산출물 출력
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../composeApp/src/androidMain/jniLibs \
  build --release
```

### 2. Desktop 타겟 컴파일

```bash
# Linux
cargo build --release
cp target/release/libsftp_sync_core.so ../composeApp/src/desktopMain/resources/

# Windows
cargo build --release --target x86_64-pc-windows-msvc
copy target\x86_64-pc-windows-msvc\release\sftp_sync_core.dll ..\composeApp\src\desktopMain\resources\
```

### 3. UniFFI Kotlin 코드 생성

```bash
cargo run --features uniffi/cli --bin uniffi-bindgen generate \
  --library target/release/libsftp_sync_core.so \
  --language kotlin \
  --out-dir ../composeApp/src/commonMain/kotlin
```

---

## 🛡️ 무중단 하이브리드 폴백 지원 (`RustCoreBridge`)

Kotlin 측 [`RustCoreBridge.kt`](../composeApp/src/commonMain/kotlin/com/sftpsync/app/rust/RustCoreBridge.kt)는 네이티브 `.so` 바이너리가 로드되지 않은 개발/테스트 환경에서도 크래시 없이 기존 Kotlin 순수 구현체로 부드럽게 대체 구동(Graceful Degradation)되도록 설계되었습니다.
