//! The object the Android app holds for its whole process lifetime.

use std::future::Future;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ondura_mail_core::model::*;
use ondura_mail_core::secrets::{password_key, refresh_token_key};
use ondura_mail_core::{MailProvider, SecretStore, Store, SyncEngine};
use ondura_mail_ews::EwsProvider;
use ondura_mail_graph::GraphProvider;
use ondura_mail_imap::ImapProvider;
use serde::{Deserialize, Serialize};
use tokio::runtime::Runtime;
use tracing::{error, info};

use crate::secrets::{SecretVault, VaultStore};
use crate::{BridgeResult, OnduraError};

/// Client id of the Entra ID app registration, injected at build time exactly
/// like the desktop build (`ONDURA_GRAPH_CLIENT_ID`). It never leaves Rust.
const DEFAULT_GRAPH_CLIENT_ID: Option<&str> = option_env!("ONDURA_GRAPH_CLIENT_ID");

const TRUSTED_CERTS_KEY: &str = "trustedCertificates";

const ACCOUNT_COLORS: [&str; 6] = ["#4f54d4", "#107c41", "#c239b3", "#ca5010", "#0e7a8a", "#6b4fbb"];

fn builtin_client_id() -> Option<&'static str> {
    DEFAULT_GRAPH_CLIENT_ID.map(str::trim).filter(|s| !s.is_empty())
}

fn effective_client_id(stored: &str) -> BridgeResult<String> {
    let stored = stored.trim();
    if !stored.is_empty() {
        return Ok(stored.to_string());
    }
    builtin_client_id()
        .map(str::to_string)
        .ok_or_else(|| ondura_i18n::tr!("Keine Anwendungs-ID (Client-ID) für Microsoft 365 hinterlegt").into())
}

/// Hides the built-in client id from everything sent to the UI.
fn redact(mut account: Account) -> Account {
    if let AccountKind::Graph(cfg) = &mut account.kind
        && builtin_client_id().is_some_and(|id| id == cfg.client_id.trim())
    {
        cfg.client_id.clear();
    }
    account
}

fn json<T: Serialize>(value: &T) -> BridgeResult<String> {
    Ok(serde_json::to_string(value)?)
}

fn parse<'a, T: Deserialize<'a>>(text: &'a str) -> BridgeResult<T> {
    Ok(serde_json::from_str(text)?)
}

/// Receives the events of the sync engine as JSON (`SyncEvent`, tagged by
/// `type`). Implemented in Kotlin.
#[uniffi::export(with_foreign)]
pub trait EventListener: Send + Sync {
    fn on_event(&self, event_json: String);
}

/// Opens a URL in the browser (Custom Tab) for the Microsoft sign-in.
/// Implemented in Kotlin; returns `false` if no browser could be opened.
#[uniffi::export(with_foreign)]
pub trait UrlOpener: Send + Sync {
    fn open_url(&self, url: String) -> bool;
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NewImapAccount {
    display_name: String,
    email: String,
    password: String,
    config: ImapConfig,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NewEwsAccount {
    display_name: String,
    email: String,
    password: String,
    config: EwsConfig,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountUpdate {
    display_name: String,
    signature: Option<String>,
    color: Option<String>,
}

/// App-wide settings. Keys are shared with the desktop app where they mean
/// the same thing (`syncIntervalMinutes`, `loadRemoteImages`, ...).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MobileSettings {
    sync_interval_minutes: u64,
    notifications: bool,
    load_remote_images: bool,
    focused_inbox: bool,
    /// "system", "light" or "dark".
    theme: String,
    /// "", "de", "en" or "fr" ("" = system language).
    language: String,
}

impl Default for MobileSettings {
    fn default() -> Self {
        Self { sync_interval_minutes: 15, notifications: true, load_remote_images: false, focused_inbox: true, theme: "system".into(), language: String::new() }
    }
}

/// Ondura Mail for Android: one instance per process.
#[derive(uniffi::Object)]
pub struct OnduraMail {
    runtime: Runtime,
    engine: Arc<SyncEngine>,
    secrets: Arc<dyn SecretStore>,
    data_dir: PathBuf,
    started: Mutex<bool>,
}

impl OnduraMail {
    fn store(&self) -> &Arc<Store> {
        self.engine.store()
    }

    /// Runs `fut` on the core's own Tokio runtime and awaits it from whatever
    /// executor the caller uses (the Kotlin coroutine bridge of UniFFI).
    async fn run<T, F>(&self, fut: F) -> BridgeResult<T>
    where
        T: Send + 'static,
        F: Future<Output = BridgeResult<T>> + Send + 'static,
    {
        self.runtime.spawn(fut).await.map_err(|e| OnduraError::Failed { reason: format!("task failed: {e}") })?
    }

    fn provider_for(account: &Account, secrets: &Arc<dyn SecretStore>) -> BridgeResult<Arc<dyn MailProvider>> {
        let me = Address::new(Some(account.display_name.clone()), &account.email);
        let password = || -> BridgeResult<String> {
            secrets
                .get(&password_key(&account.id))?
                .ok_or_else(|| OnduraError::Auth { reason: ondura_i18n::tr!("Kein Passwort gespeichert") })
        };
        Ok(match &account.kind {
            AccountKind::Imap(cfg) => ImapProvider::new(cfg.clone(), me, password()?),
            AccountKind::Ews(cfg) => EwsProvider::new(cfg.clone(), me, password()?),
            AccountKind::Graph(cfg) => {
                let client_id = effective_client_id(&cfg.client_id)?;
                match cfg.mailbox.as_deref() {
                    Some(mailbox) => GraphProvider::shared(cfg.tenant.clone(), client_id, Arc::clone(secrets), refresh_token_key(&account.id), mailbox),
                    None => GraphProvider::new(cfg.tenant.clone(), client_id, Arc::clone(secrets), refresh_token_key(&account.id)),
                }
            }
            AccountKind::Dav(_) => return Err(OnduraError::Failed { reason: "calendar-only account".into() }),
        })
    }

    /// Creates the adapter of an account and hands it to the sync engine.
    fn activate(&self, account: &Account) -> BridgeResult<()> {
        if !account.has_mail() {
            return Ok(());
        }
        // `register` spawns the inbox watcher, so it needs the runtime context.
        let _guard = self.runtime.enter();
        match Self::provider_for(account, &self.secrets) {
            Ok(p) => {
                self.engine.register(&account.id, p, true);
                Ok(())
            }
            Err(e) => {
                let core_err = match &e {
                    OnduraError::Auth { reason: message } => ondura_mail_core::Error::Auth(message.clone()),
                    OnduraError::Failed { reason: message } => ondura_mail_core::Error::Other(message.clone()),
                };
                self.engine.set_account_error(&account.id, &core_err);
                Err(e)
            }
        }
    }

    fn next_color(&self) -> String {
        let n = self.store().list_accounts().map(|a| a.len()).unwrap_or(0);
        ACCOUNT_COLORS[n % ACCOUNT_COLORS.len()].to_string()
    }

    fn sync_in_background(&self, account_id: String) {
        let engine = Arc::clone(&self.engine);
        self.runtime.spawn(async move {
            let _ = engine.sync_account(&account_id).await;
        });
    }

    fn load_trusted(store: &Store) -> Vec<String> {
        store.setting(TRUSTED_CERTS_KEY).ok().flatten().and_then(|v| serde_json::from_str(&v).ok()).unwrap_or_default()
    }

    fn settings_value(&self) -> MobileSettings {
        self.store()
            .setting("android.settings")
            .ok()
            .flatten()
            .and_then(|v| serde_json::from_str(&v).ok())
            .unwrap_or_default()
    }

    async fn graph_login(
        tenant: String,
        client_id: String,
        opener: Arc<dyn UrlOpener>,
    ) -> BridgeResult<ondura_mail_graph::auth::TokenResponse> {
        let http = ondura_mail_graph::http_client();
        ondura_mail_graph::login_interactive(&http, &tenant, &client_id, move |url| {
            if opener.open_url(url.to_string()) { Ok(()) } else { Err("no browser available".into()) }
        })
        .await
        .map_err(|e| OnduraError::Failed { reason: ondura_i18n::tr!("Microsoft-Anmeldung fehlgeschlagen: {0}", e) })
    }
}

#[uniffi::export]
impl OnduraMail {
    /// Opens (or creates) the local store in `data_dir` and loads the
    /// accounts. Network activity only starts with [`OnduraMail::start`].
    #[uniffi::constructor]
    pub fn new(data_dir: String, language: String, vault: Arc<dyn SecretVault>) -> BridgeResult<Arc<Self>> {
        crate::init_logging();
        if let Some(lang) = ondura_i18n::Lang::parse(&language) {
            ondura_i18n::set_language(lang);
        }
        let data_dir = PathBuf::from(data_dir);
        std::fs::create_dir_all(&data_dir).map_err(|e| OnduraError::Failed { reason: e.to_string() })?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("ondura-core")
            .enable_all()
            .build()
            .map_err(|e| OnduraError::Failed { reason: e.to_string() })?;
        let store = Arc::new(Store::open(data_dir.join("mail.sqlite"))?);
        ondura_mail_core::net::set_trusted_certificates(Self::load_trusted(&store));
        let engine = SyncEngine::new(Arc::clone(&store));
        let secrets: Arc<dyn SecretStore> = Arc::new(VaultStore(vault));
        let this = Arc::new(Self { runtime, engine, secrets, data_dir, started: Mutex::new(false) });
        for account in this.store().list_accounts()? {
            if let Err(e) = this.activate(&account) {
                error!(account = %account.email, error = %e, "could not activate account");
            }
        }
        info!("Ondura core ready");
        Ok(this)
    }

    /// Starts the background work (outbox flusher, periodic sync, inbox
    /// watchers) and forwards every engine event to `listener`. Idempotent.
    pub fn start(&self, listener: Arc<dyn EventListener>) {
        let mut started = self.started.lock().unwrap();
        if *started {
            return;
        }
        *started = true;
        let _guard = self.runtime.enter();
        self.engine.start();
        let mut events = self.engine.subscribe();
        self.runtime.spawn(async move {
            loop {
                match events.recv().await {
                    Ok(event) => {
                        if let Ok(text) = serde_json::to_string(&event) {
                            listener.on_event(text);
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                }
            }
        });
    }

    /// UI language for messages produced by the core ("de", "en", "fr").
    pub fn set_language(&self, language: String) {
        if let Some(lang) = ondura_i18n::Lang::parse(&language) {
            ondura_i18n::set_language(lang);
        }
    }

    /// Directory of the local database (for diagnostics).
    pub fn data_dir(&self) -> String {
        self.data_dir.to_string_lossy().into_owned()
    }

    // ------------------------------------------------------------ accounts

    pub fn has_builtin_graph_client_id(&self) -> bool {
        builtin_client_id().is_some()
    }

    /// Mail accounts as JSON `Account[]`.
    pub fn list_accounts(&self) -> BridgeResult<String> {
        let accounts: Vec<Account> = self.store().list_accounts()?.into_iter().filter(Account::has_mail).map(redact).collect();
        json(&accounts)
    }

    /// Server settings for an email address as JSON `Discovery`.
    pub async fn discover_settings(&self, email: String) -> BridgeResult<String> {
        self.run(async move {
            let found = crate::autoconfig::discover_mail_settings(email).await?;
            json(&found)
        })
        .await
    }

    /// `input_json`: `{displayName, email, password, config: ImapConfig}`.
    pub async fn add_imap_account(self: Arc<Self>, input_json: String) -> BridgeResult<String> {
        let input: NewImapAccount = parse(&input_json)?;
        let this = Arc::clone(&self);
        self.run(async move {
            let account = Account {
                id: uuid::Uuid::new_v4().to_string(),
                display_name: input.display_name.trim().to_string(),
                email: input.email.trim().to_string(),
                kind: AccountKind::Imap(input.config.clone()),
                color: Some(this.next_color()),
                signature: None,
                dav: None,
            };
            let provider = ImapProvider::new(input.config, Address::new(Some(account.display_name.clone()), &account.email), input.password.clone());
            provider
                .test_login()
                .await
                .map_err(|e| OnduraError::Failed { reason: ondura_i18n::tr!("Anmeldung am IMAP-Server fehlgeschlagen: {0}", e) })?;
            drop(provider);
            this.secrets.set(&password_key(&account.id), &input.password)?;
            this.store().upsert_account(&account)?;
            this.activate(&account)?;
            this.sync_in_background(account.id.clone());
            json(&redact(account))
        })
        .await
    }

    /// `input_json`: `{displayName, email, password, config: EwsConfig}`.
    pub async fn add_ews_account(self: Arc<Self>, input_json: String) -> BridgeResult<String> {
        let input: NewEwsAccount = parse(&input_json)?;
        let this = Arc::clone(&self);
        self.run(async move {
            let account = Account {
                id: uuid::Uuid::new_v4().to_string(),
                display_name: input.display_name.trim().to_string(),
                email: input.email.trim().to_string(),
                kind: AccountKind::Ews(input.config.clone()),
                color: Some(this.next_color()),
                signature: None,
                dav: None,
            };
            let provider = EwsProvider::new(input.config, Address::new(Some(account.display_name.clone()), &account.email), input.password.clone());
            provider
                .test_login()
                .await
                .map_err(|e| OnduraError::Failed { reason: ondura_i18n::tr!("Verbindung zum Exchange-Server fehlgeschlagen: {0}", e) })?;
            drop(provider);
            this.secrets.set(&password_key(&account.id), &input.password)?;
            this.store().upsert_account(&account)?;
            this.activate(&account)?;
            this.sync_in_background(account.id.clone());
            json(&redact(account))
        })
        .await
    }

    /// Microsoft 365 / Outlook.com sign-in in the browser (OAuth code flow with
    /// PKCE and a loopback redirect, the same flow as on the desktop).
    /// `tenant`: "common", "organizations" or a tenant id.
    pub async fn add_graph_account(self: Arc<Self>, tenant: String, opener: Arc<dyn UrlOpener>) -> BridgeResult<String> {
        let this = Arc::clone(&self);
        self.run(async move {
            let tenant = if tenant.trim().is_empty() { "common".to_string() } else { tenant.trim().to_string() };
            let client_id = effective_client_id("")?;
            let token = Self::graph_login(tenant.clone(), client_id.clone(), opener).await?;
            let id = uuid::Uuid::new_v4().to_string();
            let provider = GraphProvider::new(tenant.clone(), client_id, Arc::clone(&this.secrets), refresh_token_key(&id));
            provider.tokens().store(&token).await?;
            let me = provider.me().await?;
            drop(provider);
            let account = Account {
                id,
                display_name: me.display_name.clone().unwrap_or_else(|| me.email()),
                email: me.email(),
                kind: AccountKind::Graph(GraphConfig { tenant, client_id: String::new(), mailbox: None }),
                color: Some(this.next_color()),
                signature: None,
                dav: None,
            };
            this.store().upsert_account(&account)?;
            this.activate(&account)?;
            this.sync_in_background(account.id.clone());
            json(&redact(account))
        })
        .await
    }

    /// Sign in to Microsoft again (expired or revoked refresh token).
    pub async fn reauth_graph(self: Arc<Self>, account_id: String, opener: Arc<dyn UrlOpener>) -> BridgeResult<()> {
        let this = Arc::clone(&self);
        self.run(async move {
            let account = this.store().get_account(&account_id)?;
            let AccountKind::Graph(cfg) = &account.kind else {
                return Err(ondura_i18n::tr!("Nur für Microsoft-365-Konten").into());
            };
            let client_id = effective_client_id(&cfg.client_id)?;
            let token = Self::graph_login(cfg.tenant.clone(), client_id.clone(), opener).await?;
            let provider = GraphProvider::new(cfg.tenant.clone(), client_id, Arc::clone(&this.secrets), refresh_token_key(&account_id));
            provider.tokens().store(&token).await?;
            drop(provider);
            this.activate(&account)?;
            this.engine.retry_pending(&account_id)?;
            this.sync_in_background(account_id);
            Ok(())
        })
        .await
    }

    /// New password for an IMAP or Exchange account.
    pub async fn update_password(self: Arc<Self>, account_id: String, password: String) -> BridgeResult<()> {
        let this = Arc::clone(&self);
        self.run(async move {
            let account = this.store().get_account(&account_id)?;
            let from = Address::new(Some(account.display_name.clone()), &account.email);
            let failed = |e: ondura_mail_core::Error| OnduraError::Failed { reason: ondura_i18n::tr!("Anmeldung fehlgeschlagen: {0}", e) };
            match &account.kind {
                AccountKind::Imap(cfg) => ImapProvider::new(cfg.clone(), from, password.clone()).test_login().await.map_err(failed)?,
                AccountKind::Ews(cfg) => EwsProvider::new(cfg.clone(), from, password.clone()).test_login().await.map_err(failed)?,
                _ => return Err(ondura_i18n::tr!("Microsoft-365-Konten melden sich im Browser an").into()),
            }
            this.secrets.set(&password_key(&account_id), &password)?;
            this.activate(&account)?;
            this.engine.retry_pending(&account_id)?;
            this.sync_in_background(account_id);
            Ok(())
        })
        .await
    }

    /// `update_json`: `{displayName, signature?, color?}`. Returns the account.
    pub fn update_account(&self, account_id: String, update_json: String) -> BridgeResult<String> {
        let update: AccountUpdate = parse(&update_json)?;
        let mut account = self.store().get_account(&account_id)?;
        let name_changed = account.display_name != update.display_name.trim();
        account.display_name = update.display_name.trim().to_string();
        account.signature = update.signature.filter(|s| !s.trim().is_empty());
        if update.color.is_some() {
            account.color = update.color;
        }
        self.store().upsert_account(&account)?;
        if name_changed {
            // The sender name is part of the IMAP adapter.
            let _ = self.activate(&account);
        }
        json(&redact(account))
    }

    pub fn remove_account(&self, account_id: String) -> BridgeResult<()> {
        self.engine.unregister(&account_id);
        let _ = self.secrets.delete(&password_key(&account_id));
        let _ = self.secrets.delete(&refresh_token_key(&account_id));
        self.store().delete_account(&account_id)?;
        Ok(())
    }

    /// JSON `AccountStatus[]`.
    pub fn account_statuses(&self) -> BridgeResult<String> {
        json(&self.engine.statuses())
    }

    // ------------------------------------------------------------ certificates

    /// Certificate of a server for the "trust this certificate?" dialog.
    /// `mode`: "direct", "imapStartTls" or "smtpStartTls". JSON `CertificateInfo`.
    pub async fn probe_certificate(&self, host: String, port: u16, mode: String) -> BridgeResult<String> {
        self.run(async move {
            use ondura_mail_core::net::ProbeMode;
            let mode = match mode.as_str() {
                "imapStartTls" => ProbeMode::ImapStartTls,
                "smtpStartTls" => ProbeMode::SmtpStartTls,
                _ => ProbeMode::Direct,
            };
            json(&ondura_mail_core::net::probe_certificate(host.trim(), port, mode).await?)
        })
        .await
    }

    pub fn set_certificate_trust(&self, fingerprint: String, trusted: bool) -> BridgeResult<()> {
        let fp = fingerprint.trim().to_uppercase();
        let mut all = Self::load_trusted(self.store());
        all.retain(|f| f != &fp);
        if trusted {
            all.push(fp);
        }
        self.store().set_setting(TRUSTED_CERTS_KEY, &json(&all)?)?;
        ondura_mail_core::net::set_trusted_certificates(all);
        Ok(())
    }

    // ------------------------------------------------------------ folders and lists

    /// JSON `Folder[]`.
    pub fn list_folders(&self, account_id: String) -> BridgeResult<String> {
        json(&self.store().list_folders(&account_id)?)
    }

    /// JSON `MessageSummary[]`, newest first. `focused`: `None` = all.
    pub fn list_messages(&self, folder_id: String, offset: u32, limit: u32, focused: Option<bool>) -> BridgeResult<String> {
        json(&self.engine.list_messages(&folder_id, offset, limit, focused)?)
    }

    /// Unread counts `[focused, other]` of a folder.
    pub fn focus_unread(&self, folder_id: String) -> BridgeResult<Vec<u32>> {
        let (focused, other) = self.engine.focus_unread(&folder_id)?;
        Ok(vec![focused, other])
    }

    /// Moves a message between "Focused" and "Other"; `always` also for
    /// future messages of the sender.
    pub fn set_focused(&self, message_id: String, focused: bool, always: bool) -> BridgeResult<()> {
        Ok(self.engine.set_focused(&message_id, focused, always)?)
    }

    /// JSON `MessageSummary[]` of flagged messages of all accounts.
    pub fn flagged_messages(&self, limit: u32) -> BridgeResult<String> {
        json(&self.engine.flagged(limit)?)
    }

    /// Messages of the same conversation, JSON `MessageSummary[]`.
    pub fn conversation(&self, message_id: String) -> BridgeResult<String> {
        json(&self.engine.conversation(&message_id)?)
    }

    /// Downloads older messages of a folder; returns how many were added.
    pub async fn load_older(&self, folder_id: String, limit: u32) -> BridgeResult<u32> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.load_older(&folder_id, (limit as usize).clamp(1, 500)).await? as u32) }).await
    }

    /// Full-text search in the local store, JSON `MessageSummary[]`.
    pub fn search_local(&self, query: String, account_id: Option<String>, limit: u32) -> BridgeResult<String> {
        json(&self.store().search(&query, account_id.as_deref(), limit)?)
    }

    /// Search on the servers of all accounts, JSON `MessageSummary[]`.
    pub async fn search_server(&self, query: String, limit: u32) -> BridgeResult<String> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { json(&engine.search_server(&query, limit as usize).await?) }).await
    }

    // ------------------------------------------------------------ reading

    /// Message with body (downloaded if needed), JSON `MessageDetail`.
    pub async fn get_message(&self, message_id: String) -> BridgeResult<String> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { json(&engine.get_message(&message_id).await?) }).await
    }

    /// Writes an attachment to `target_path` (a file in the app cache, shared
    /// from there through a FileProvider).
    pub async fn save_attachment(&self, message_id: String, attachment_id: String, target_path: String) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move {
            let bytes = engine.get_attachment(&message_id, &attachment_id).await?;
            tokio::fs::write(&target_path, bytes).await.map_err(|e| OnduraError::Failed { reason: e.to_string() })?;
            Ok(())
        })
        .await
    }

    // ------------------------------------------------------------ actions

    pub async fn set_read(&self, message_id: String, read: bool) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.set_read(&message_id, read).await?) }).await
    }

    pub async fn set_flagged(&self, message_id: String, flagged: bool) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.set_flagged(&message_id, flagged).await?) }).await
    }

    pub fn set_pinned(&self, message_id: String, pinned: bool) -> BridgeResult<()> {
        Ok(self.engine.set_pinned(&message_id, pinned)?)
    }

    pub async fn move_message(&self, message_id: String, target_folder_id: String) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.move_message(&message_id, &target_folder_id).await?) }).await
    }

    /// Moves to the trash, or deletes for good when already in the trash.
    pub async fn delete_message(&self, message_id: String) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.delete_message(&message_id).await?) }).await
    }

    pub async fn archive_message(&self, message_id: String) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.archive_message(&message_id).await?) }).await
    }

    /// Snoozes until the given unix time (seconds); `None` wakes it again.
    pub async fn snooze_message(&self, message_id: String, until_unix: Option<i64>) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move {
            let until = until_unix.and_then(|s| chrono::DateTime::<chrono::Utc>::from_timestamp(s, 0));
            Ok(engine.snooze(&message_id, until).await?)
        })
        .await
    }

    // ------------------------------------------------------------ sending

    /// Queues `message_json` (`OutgoingMessage`); the outcome arrives as a
    /// `messageSent` / `sendFailed` event.
    pub async fn send_message(&self, account_id: String, message_json: String) -> BridgeResult<()> {
        let message: OutgoingMessage = parse(&message_json)?;
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.send(&account_id, &message).await?) }).await
    }

    pub async fn save_draft(&self, account_id: String, message_json: String) -> BridgeResult<()> {
        let message: OutgoingMessage = parse(&message_json)?;
        let engine = Arc::clone(&self.engine);
        self.run(async move { Ok(engine.save_draft(&account_id, &message).await?) }).await
    }

    /// JSON `OutboxItem[]`.
    pub fn outbox(&self, account_id: String) -> BridgeResult<String> {
        json(&self.engine.outbox(&account_id)?)
    }

    pub fn retry_pending(&self, account_id: String) -> BridgeResult<()> {
        Ok(self.engine.retry_pending(&account_id)?)
    }

    pub fn discard_pending(&self, account_id: String, op_id: i64) -> BridgeResult<()> {
        Ok(self.engine.discard_op(&account_id, op_id)?)
    }

    /// Addresses for the recipient field: people written to or received from
    /// before, then the organisation's directory. JSON `Address[]`.
    pub async fn suggest_recipients(&self, query: String, limit: u32) -> BridgeResult<String> {
        let engine = Arc::clone(&self.engine);
        self.run(async move {
            let mut out: Vec<Address> = engine.store().frequent_addresses(&query, limit)?;
            if out.len() < limit as usize && query.trim().len() >= 3 {
                let people = tokio::time::timeout(Duration::from_secs(5), engine.search_people(&query, limit as usize)).await;
                if let Ok(Ok(people)) = people {
                    for p in people {
                        if !out.iter().any(|a| a.email.eq_ignore_ascii_case(&p.email)) {
                            out.push(Address::new(Some(p.name), p.email));
                        }
                    }
                }
            }
            out.truncate(limit as usize);
            json(&out)
        })
        .await
    }

    // ------------------------------------------------------------ sync

    /// Starts a sync of one account in the background.
    pub fn sync_account(&self, account_id: String) {
        self.sync_in_background(account_id);
    }

    /// Starts a sync of one folder in the background.
    pub fn sync_folder(&self, folder_id: String) {
        let engine = Arc::clone(&self.engine);
        self.runtime.spawn(async move {
            let _ = engine.sync_folder(&folder_id).await;
        });
    }

    /// Syncs every account and sends what is queued, and only returns when
    /// done (or after `timeout_seconds`). Used by the periodic WorkManager
    /// job while the app is in the background. New mail is reported through
    /// the event listener as usual.
    pub async fn sync_all_now(&self, timeout_seconds: u32) -> BridgeResult<()> {
        let engine = Arc::clone(&self.engine);
        self.run(async move {
            let work = async {
                for account_id in engine.account_ids() {
                    let _ = engine.flush_account(&account_id).await;
                    let _ = engine.sync_account(&account_id).await;
                }
            };
            let _ = tokio::time::timeout(Duration::from_secs(timeout_seconds.max(10) as u64), work).await;
            Ok(())
        })
        .await
    }

    // ------------------------------------------------------------ settings

    /// JSON `MobileSettings`.
    pub fn get_settings(&self) -> BridgeResult<String> {
        json(&self.settings_value())
    }

    pub fn save_settings(&self, settings_json: String) -> BridgeResult<()> {
        let mut s: MobileSettings = parse(&settings_json)?;
        s.sync_interval_minutes = s.sync_interval_minutes.clamp(15, 720);
        self.store().set_setting("android.settings", &json(&s)?)?;
        // Read by the engine's periodic full sync while the app is open.
        self.store().set_setting("syncIntervalMinutes", &s.sync_interval_minutes.min(120).to_string())?;
        self.store().set_setting("loadRemoteImages", &s.load_remote_images.to_string())?;
        Ok(())
    }
}
