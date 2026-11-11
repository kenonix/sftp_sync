use crate::models::NativeBandwidthConfig;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Mutex;
use std::time::Instant;

struct TokenBucket {
    limit_bytes_per_sec: AtomicI64,
    state: Mutex<BucketState>,
}

struct BucketState {
    available_tokens: f64,
    last_refill: Instant,
}

impl TokenBucket {
    fn new() -> Self {
        Self {
            limit_bytes_per_sec: AtomicI64::new(0),
            state: Mutex::new(BucketState {
                available_tokens: 0.0,
                last_refill: Instant::now(),
            }),
        }
    }

    fn set_limit(&self, bytes_per_sec: i64) {
        self.limit_bytes_per_sec.store(bytes_per_sec, Ordering::SeqCst);
        if let Ok(mut state) = self.state.lock() {
            state.available_tokens = 0.0;
            state.last_refill = Instant::now();
        }
    }

    fn throttle(&self, bytes: i64, enabled: &AtomicBool) {
        let limit = self.limit_bytes_per_sec.load(Ordering::Relaxed);
        if bytes <= 0 || limit <= 0 || !enabled.load(Ordering::Relaxed) {
            return;
        }

        let mut remaining = bytes;
        while remaining > 0 && enabled.load(Ordering::Relaxed) {
            let cur_limit = self.limit_bytes_per_sec.load(Ordering::Relaxed);
            if cur_limit <= 0 {
                return;
            }

            let step = remaining.min((cur_limit / 10).clamp(1024, 16384));
            let wait_duration = {
                if let Ok(mut state) = self.state.lock() {
                    let now = Instant::now();
                    let elapsed_sec = now.duration_since(state.last_refill).as_secs_f64();
                    state.last_refill = now;

                    let max_burst = (cur_limit as f64 * 0.1).clamp(1024.0, 65536.0);
                    state.available_tokens = (state.available_tokens + elapsed_sec * cur_limit as f64).min(max_burst);
                    state.available_tokens -= step as f64;

                    if state.available_tokens < 0.0 {
                        let deficit = -state.available_tokens;
                        std::time::Duration::from_secs_f64(deficit / cur_limit as f64)
                    } else {
                        std::time::Duration::ZERO
                    }
                } else {
                    std::time::Duration::ZERO
                }
            };

            remaining -= step;

            if !wait_duration.is_zero() {
                std::thread::sleep(wait_duration);
            }
        }
    }
}

#[derive(uniffi::Object)]
pub struct NativeBandwidthLimiter {
    enabled: AtomicBool,
    block_metered_download: AtomicBool,
    upload_bucket: TokenBucket,
    download_bucket: TokenBucket,
}

#[uniffi::export]
impl NativeBandwidthLimiter {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            enabled: AtomicBool::new(false),
            block_metered_download: AtomicBool::new(false),
            upload_bucket: TokenBucket::new(),
            download_bucket: TokenBucket::new(),
        }
    }

    pub fn apply_config(&self, config: NativeBandwidthConfig) {
        self.enabled.store(config.enabled, Ordering::SeqCst);
        self.block_metered_download.store(config.block_metered_download, Ordering::SeqCst);

        let upload_bytes = if config.enabled && config.upload_limit_kbps > 0 {
            config.upload_limit_kbps * 1024
        } else {
            0
        };

        let download_bytes = if config.enabled && config.download_limit_kbps > 0 {
            config.download_limit_kbps * 1024
        } else {
            0
        };

        self.upload_bucket.set_limit(upload_bytes);
        self.download_bucket.set_limit(download_bytes);
    }

    pub fn throttle_upload(&self, bytes: i64) {
        self.upload_bucket.throttle(bytes, &self.enabled);
    }

    pub fn throttle_download(&self, bytes: i64) {
        self.download_bucket.throttle(bytes, &self.enabled);
    }

    pub fn should_block_download(&self, is_metered: bool) -> bool {
        self.block_metered_download.load(Ordering::Relaxed) && is_metered
    }
}
