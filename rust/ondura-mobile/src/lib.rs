//! Bridge between the Ondura Android apps and the shared Rust core.
//!
//! The Android apps (Kotlin, Jetpack Compose) never talk to mail servers
//! themselves. Everything – the local SQLite store, the sync engine, the
//! protocol adapters for Microsoft 365 (Graph), Exchange (EWS) and IMAP/SMTP –
//! is the very same code the desktop apps of Ondura-Core run.
//!
//! The interface is generated with UniFFI. Domain objects cross the boundary
//! as JSON in exactly the shape the desktop frontend receives (camelCase,
//! serde), so both sides share one data contract and the Kotlin models mirror
//! `ondura_mail_core::model`.

uniffi::setup_scaffolding!();

mod autoconfig;
mod bridge;
mod secrets;

use std::time::Duration;

pub use bridge::{EventListener, OnduraMail, UrlOpener};
pub use secrets::SecretVault;

/// Error returned to Kotlin. `Auth` means the account needs a new password or
/// sign-in; everything else is a plain, already translated message.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum OnduraError {
    #[error("{reason}")]
    Auth { reason: String },
    #[error("{reason}")]
    Failed { reason: String },
}

impl From<ondura_mail_core::Error> for OnduraError {
    fn from(e: ondura_mail_core::Error) -> Self {
        match e {
            ondura_mail_core::Error::Auth(message) => OnduraError::Auth { reason: message },
            other => OnduraError::Failed { reason: other.to_string() },
        }
    }
}

impl From<serde_json::Error> for OnduraError {
    fn from(e: serde_json::Error) -> Self {
        OnduraError::Failed { reason: format!("invalid data: {e}") }
    }
}

impl From<String> for OnduraError {
    fn from(message: String) -> Self {
        OnduraError::Failed { reason: message }
    }
}

pub(crate) type BridgeResult<T> = Result<T, OnduraError>;

/// HTTP client with the TLS setup of the core (rustls + Mozilla roots + the
/// certificates the user trusted).
pub(crate) fn http_client(timeout: Option<Duration>) -> reqwest::Client {
    let mut tls = (*ondura_mail_core::net::tls_client_config()).clone();
    tls.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    let mut b = reqwest::Client::builder()
        .tls_backend_preconfigured(tls)
        .user_agent(concat!("Ondura-Android/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(20));
    if let Some(t) = timeout {
        b = b.timeout(t);
    }
    b.build().expect("reqwest client")
}

/// Routes `tracing` output to logcat (Android) or stderr (tests, host builds).
pub(crate) fn init_logging() {
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(android_logger::Config::default().with_tag("OnduraCore").with_max_level(log::LevelFilter::Info));
        let _ = tracing_log::LogTracer::init();
        let _ = tracing_subscriber::fmt()
            .with_env_filter(tracing_subscriber::EnvFilter::new("info"))
            .with_writer(LogcatWriter)
            .with_ansi(false)
            .without_time()
            .try_init();
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into())).try_init();
    }
}

#[cfg(target_os = "android")]
struct LogcatWriter;

#[cfg(target_os = "android")]
impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for LogcatWriter {
    type Writer = LogcatLine;
    fn make_writer(&'a self) -> Self::Writer {
        LogcatLine(Vec::new())
    }
}

#[cfg(target_os = "android")]
struct LogcatLine(Vec<u8>);

#[cfg(target_os = "android")]
impl std::io::Write for LogcatLine {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[cfg(target_os = "android")]
impl Drop for LogcatLine {
    fn drop(&mut self) {
        let line = String::from_utf8_lossy(&self.0);
        let line = line.trim_end();
        if !line.is_empty() {
            log::info!("{line}");
        }
    }
}
