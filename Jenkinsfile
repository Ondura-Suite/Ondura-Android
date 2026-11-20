// Jenkins pipeline for the Ondura Android apps (builds.mirranet.de).
//
// Produces the release APK of Ondura Mail.
//
// Agent requirements, see docs/development.md "Continuous integration":
//   JDK 17, Android SDK (ANDROID_HOME) with platforms;android-35,
//   build-tools;35.0.0 and ndk;27.2.12479018, Rust (rustup, stable >= 1.90)
//   with the Android targets, and `cargo install --locked cargo-ndk`.

pipeline {
    agent any

    options {
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 90, unit: 'MINUTES')
    }

    parameters {
        string(name: 'GRAPH_CLIENT_ID', defaultValue: '', description: 'Client id of the Entra ID app registration baked into the build (same as the desktop build)')
        string(name: 'KEYSTORE_CREDENTIALS_ID', defaultValue: 'ondura-android-keystore', description: 'Jenkins "secret file" credential with the release keystore (empty: debug signing)')
        string(name: 'KEYSTORE_PASSWORD_CREDENTIALS_ID', defaultValue: 'ondura-android-keystore-password', description: 'Jenkins "secret text" credential with the keystore password')
    }

    environment {
        PATH = "${env.HOME}/.cargo/bin:${env.PATH}"
        CARGO_TERM_COLOR = 'never'
        ONDURA_GRAPH_CLIENT_ID = "${params.GRAPH_CLIENT_ID}"
    }

    stages {
        stage('Clean') {
            steps {
                // Keep rust/target between builds (cargo cache); reset everything else.
                sh 'git clean -ffdx -e /rust/target/ -e /.gradle/'
            }
        }

        stage('Toolchain') {
            steps {
                sh '''
                    set -e
                    java -version
                    rustc --version
                    cargo ndk --version
                    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
                    test -n "$ANDROID_HOME" || { echo "ANDROID_HOME is not set"; exit 1; }
                '''
            }
        }

        stage('Rust bridge') {
            steps {
                dir('rust') {
                    sh 'cargo clippy -p ondura-mobile -- -D warnings'
                }
            }
        }

        stage('Build') {
            steps {
                script {
                    def build = { ->
                        sh './gradlew --no-daemon :mail:assembleRelease'
                    }
                    if (params.KEYSTORE_CREDENTIALS_ID?.trim()) {
                        try {
                            withCredentials([
                                file(credentialsId: params.KEYSTORE_CREDENTIALS_ID, variable: 'ONDURA_KEYSTORE_FILE'),
                                string(credentialsId: params.KEYSTORE_PASSWORD_CREDENTIALS_ID, variable: 'ONDURA_KEYSTORE_PASSWORD'),
                            ]) {
                                build()
                            }
                        } catch (org.jenkinsci.plugins.credentialsbinding.impl.CredentialNotFoundException e) {
                            echo "Keystore credentials not found – building with debug signing"
                            build()
                        }
                    } else {
                        build()
                    }
                }
                sh '''
                    set -e
                    version=$(grep '^ondura.versionName=' gradle.properties | cut -d= -f2)
                    mkdir -p dist
                    cp mail/build/outputs/apk/release/mail-release.apk "dist/Ondura-Mail-${version}-${BUILD_NUMBER}.apk"
                '''
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'dist/*.apk', fingerprint: true
        }
    }
}
