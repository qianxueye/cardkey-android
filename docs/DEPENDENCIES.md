# Public dependency inventory

Original Kotlin protocol and reader implementations were written for this repository from public specifications. No private runtime sources, applet CAP files, physical-card captures or private test fixtures are included. Protocol test keys and certificates are generated in tests.

| Dependency | Pin | License / role |
| --- | --- | --- |
| Gradle wrapper | 8.13 | Apache-2.0; build tool |
| Android Gradle Plugin | 8.13.2 | Apache-2.0; Android packaging |
| Kotlin and Compose compiler plugin | 2.3.0 | Apache-2.0 |
| Compose BOM | 2025.10.01 | Apache-2.0; aligned Compose dependencies |
| Compose Material 3 | 1.4.0 | Apache-2.0; official Material components |
| AndroidX Activity Compose | 1.11.0 | Apache-2.0 |
| AndroidX Lifecycle | 2.9.4 | Apache-2.0 |
| Kotlin Coroutines Android | 1.10.2 | Apache-2.0 |
| Bouncy Castle PKIX/provider/util | 1.83 | Bouncy Castle MIT-style license; CSR/CICC ASN.1, JCA support and CS7 AES-CMAC |
| AndroidX Credentials | 1.6.0 | Apache-2.0; Android 14+ provider APIs |
| AndroidX Fragment constraint | 1.8.9 | Apache-2.0; compatible Activity Result dependency for Credentials |
| Gson | 2.13.1 | Apache-2.0; strict bounded JSON parsing and response encoding |
| OkHttp | 4.12.0 | Apache-2.0; native DAL HTTPS only |
| AndroidX Test runner / core / JUnit extension | 1.6.2 / 1.6.1 / 1.2.1 | Apache-2.0; scripted instrumentation only |
| JUnit | 4.13.2 | EPL-1.0; tests only |
| Hamcrest Core | 1.3 (JUnit transitive) | BSD-3-Clause; tests only |

AndroidX, Kotlin standard library, JetBrains annotations and JSpecify transitives use their published public Maven metadata. The build resolves only Google Maven, Maven Central and the public Gradle Plugin Portal. Pinned tool and library versions are visible in the Gradle scripts; network permission is used only for native-app Digital Asset Links HTTPS verification.

License texts shipped inside third-party JAR/AAR files remain attached to their respective artifacts. The Bouncy Castle notice is also retained under `licenses/BOUNCY-CASTLE.txt`.

Primary implementation references:

- [NIST SP 800-73 Part 2, July 2024](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-73pt2-5.pdf) — CS7 establishment and secure messaging
- [NIST SP 800-73 Part 1, July 2024](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-73pt1-5.pdf) — PIV objects and access policy
- [NIST SP 800-78-5, July 2024](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-78-5.pdf) — cryptographic algorithms
- [CTAP 2.1](https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-20210615.html)
- [USB CCID 1.1](https://www.usb.org/sites/default/files/DWG_Smart-Card_CCID_Rev110.pdf)
- [Android IsoDep](https://developer.android.com/reference/android/nfc/tech/IsoDep)
- [Android UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
- [PIV metadata APDU](https://docs.yubico.com/yesdk/users-manual/application-piv/apdu/metadata.html)
- [PIV mutual management authentication](https://docs.yubico.com/yesdk/users-manual/application-piv/apdu/auth-mgmt.html)
- [PIV management-key change](https://docs.yubico.com/yesdk/users-manual/application-piv/apdu/set-mgmt-key.html)
- [PIV key generation](https://docs.yubico.com/yesdk/users-manual/application-piv/apdu/generate-pair.html)
- [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Material 3 release record](https://developer.android.com/jetpack/androidx/releases/compose-material3)

The chosen library set stays within the verified AGP 8.13.2 / API 36 toolchain. Newer-version lint notices do not change these pins automatically. Bouncy Castle includes a TLS helper that Android lint identifies as a permissive trust manager; this app never invokes that helper. DAL networking uses OkHttp with Android platform TLS trust and hostname validation. Local card signatures are verified explicitly and are not presented as certificate-chain trust.

Credentials brings Fragment transitively. Its version is constrained to 1.8.9 because versions below 1.3.0 do not support the Activity Result API contract used by the app. [Official Fragment releases](https://developer.android.com/jetpack/androidx/releases/fragment#1.8.9)

The Credentials library brings biometric support transitively, but this hardware provider does not invoke it. The manifest removes its unused biometric permissions; it never substitutes a phone biometric result for on-card UV.
