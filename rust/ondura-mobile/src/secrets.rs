//! Passwords and OAuth refresh tokens.
//!
//! The Android side implements [`SecretVault`] on top of the Android Keystore
//! (AES-GCM key that never leaves the secure hardware); the core only sees it
//! through its own `SecretStore` trait. Secrets never end up in SQLite.

use std::sync::Arc;

use ondura_mail_core::{Error, Result, SecretStore};

/// Implemented in Kotlin.
#[uniffi::export(with_foreign)]
pub trait SecretVault: Send + Sync {
    fn get(&self, key: String) -> Option<String>;
    /// `false` if the value could not be stored.
    fn set(&self, key: String, value: String) -> bool;
    fn delete(&self, key: String);
}

pub(crate) struct VaultStore(pub Arc<dyn SecretVault>);

impl SecretStore for VaultStore {
    fn get(&self, key: &str) -> Result<Option<String>> {
        Ok(self.0.get(key.to_string()))
    }

    fn set(&self, key: &str, value: &str) -> Result<()> {
        if self.0.set(key.to_string(), value.to_string()) {
            Ok(())
        } else {
            Err(Error::Other("could not store the secret in the Android Keystore".into()))
        }
    }

    fn delete(&self, key: &str) -> Result<()> {
        self.0.delete(key.to_string());
        Ok(())
    }
}
