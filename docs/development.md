# Development

## Layout

```
rust/                 Rust workspace
  ondura-mobile/      bridge over the Ondura-Core crates (UniFFI)
  uniffi-bindgen/     generates the Kotlin bindings from the built library
core/                 Android library: Rust core + Kotlin models (MailCore)
ui/                   Ondura design for Compose (colors, avatars)
mail/                 Ondura Mail (Jetpack Compose)
scripts/strings.py    string resources in English, German and French
```

The Rust crates of [Ondura-Core](https://github.com/Ondura-Suite/Ondura-Core)
(`mail-core`, `mail-graph`, `mail-ews`, `mail-imap`, `i18n`) are pulled in as git
dependencies pinned to a commit (`rust/ondura-mobile/Cargo.toml`). To pick up
changes from the core, bump `rev` of all of them and run `cargo update` in
`rust/`.

The bridge exposes one object, `OnduraMail`. Domain objects cross the boundary
as JSON in exactly the shape the desktop frontend gets, and
`core/src/main/java/.../Models.kt` mirrors `ondura_mail_core::model`. Kotlin code
only ever talks to `MailCore`, which wraps the generated bindings.

`rust/ondura-mobile/src/autoconfig.rs` is a copy of the desktop app's server
discovery (`apps/ondura/src-tauri/src/autoconfig.rs`); keep both in step.

## Requirements

- JDK 17
- Android SDK with `platforms;android-35`, `build-tools;35.0.0` and
  `ndk;27.2.12479018`
- Rust (stable ≥ 1.90) with the Android targets and cargo-ndk:

  ```sh
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install --locked cargo-ndk
  ```

## Build

```sh
./gradlew :mail:assembleDebug                       # all ABIs
./gradlew :mail:installDebug -Pondura.abis=arm64-v8a  # quick, one ABI, onto a phone
./gradlew :mail:assembleRelease
```

Gradle builds the Rust library (`:core:cargoBuild`) and generates the Kotlin
bindings (`:core:uniffiBindings`) before compiling.

### Microsoft 365 sign-in

Like the desktop build, the client id of the Entra ID app registration is
baked in at build time:

```sh
ONDURA_GRAPH_CLIENT_ID=<client id> ./gradlew :mail:assembleRelease
```

The app uses the same sign-in flow as the desktop (authorization code with
PKCE and a `http://localhost` redirect), so the existing app registration works
as it is – no extra redirect URI is needed.

### Signing

Release builds are signed with the keystore given in the environment:

| Variable | |
|---|---|
| `ONDURA_KEYSTORE_FILE` | path of the `.jks` file |
| `ONDURA_KEYSTORE_PASSWORD` | keystore password |
| `ONDURA_KEY_ALIAS` | key alias (default `ondura`) |
| `ONDURA_KEY_PASSWORD` | key password (default: keystore password) |

Without a keystore the release APK is signed with the debug key. Android only
installs updates signed with the same key, so use one fixed keystore for
everything you hand out:

```sh
keytool -genkeypair -v -keystore ondura.jks -alias ondura -keyalg RSA -keysize 4096 -validity 10000
```

## Continuous integration

**GitHub Actions** (`.github/workflows/build.yml`) builds the release APK on
every push and attaches it to a GitHub release for tags `v*` (the tag sets the
version name). Repository or organisation settings:

- variable `ONDURA_GRAPH_CLIENT_ID`
- secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 ondura.jks`),
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`

**Jenkins** (`Jenkinsfile`, builds.mirranet.de) needs the requirements above on
the agent and `ANDROID_HOME` set. The keystore comes from the credentials
`ondura-android-keystore` (secret file) and `ondura-android-keystore-password`
(secret text); without them the build uses debug signing. The client id is a
build parameter.

## Screenshots

`docs/screenshots` is rendered from the real Compose screens with sample data
(`mail/src/test/.../Screenshots.kt`, Robolectric):

```sh
./gradlew :mail:testDebugUnitTest --tests '*Screenshots*'   # → mail/build/screenshots
```

CI renders them on every build and uploads them as the `screenshots` artifact.
The images in the repository are scaled to 600 px width.

## Translations

Edit the table in `scripts/strings.py` and run `python3 scripts/strings.py`.
Messages produced by the core (errors) are translated in Ondura-Core
(`crates/i18n`).
