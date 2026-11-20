//! Smoke test of the bridge on the host: the object the Android app creates
//! must come up on an empty data directory and answer in the JSON shapes the
//! Kotlin models expect.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use ondura_mobile::{EventListener, OnduraMail, SecretVault};

#[derive(Default)]
struct Vault(Mutex<HashMap<String, String>>);

impl SecretVault for Vault {
    fn get(&self, key: String) -> Option<String> {
        self.0.lock().unwrap().get(&key).cloned()
    }
    fn set(&self, key: String, value: String) -> bool {
        self.0.lock().unwrap().insert(key, value);
        true
    }
    fn delete(&self, key: String) {
        self.0.lock().unwrap().remove(&key);
    }
}

struct Events;

impl EventListener for Events {
    fn on_event(&self, _event_json: String) {}
}

fn temp_dir() -> String {
    let dir = std::env::temp_dir().join(format!("ondura-mobile-test-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir.to_string_lossy().into_owned()
}

#[test]
fn starts_on_an_empty_store() {
    let dir = temp_dir();
    let mail = OnduraMail::new(dir.clone(), "de".into(), Arc::new(Vault::default())).expect("core starts");
    mail.start(Arc::new(Events));
    mail.start(Arc::new(Events)); // idempotent

    assert_eq!(mail.list_accounts().unwrap(), "[]");
    assert_eq!(mail.account_statuses().unwrap(), "[]");

    let settings: serde_json::Value = serde_json::from_str(&mail.get_settings().unwrap()).unwrap();
    assert_eq!(settings["syncIntervalMinutes"], 15);
    assert_eq!(settings["focusedInbox"], true);

    mail.save_settings(r#"{"syncIntervalMinutes":5,"notifications":false,"loadRemoteImages":true,"focusedInbox":false,"theme":"dark","language":"fr"}"#.into())
        .unwrap();
    let settings: serde_json::Value = serde_json::from_str(&mail.get_settings().unwrap()).unwrap();
    assert_eq!(settings["syncIntervalMinutes"], 15, "clamped to the WorkManager minimum");
    assert_eq!(settings["theme"], "dark");

    assert!(mail.list_folders("missing".into()).unwrap() == "[]");
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn async_calls_run_on_the_core_runtime() {
    let dir = temp_dir() + "-async";
    let mail = OnduraMail::new(dir.clone(), "en".into(), Arc::new(Vault::default())).expect("core starts");
    // Awaited from a different executor than the core's own runtime, like
    // the Kotlin coroutine bridge does.
    let result = futures::executor::block_on(mail.get_message("does-not-exist".into()));
    assert!(result.is_err());
    let _ = std::fs::remove_dir_all(dir);
}
