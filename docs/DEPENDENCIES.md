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
| Bouncy Castle PKIX/provider/util | 1.83 | Bouncy Castle MIT-style license; CSR ASN.1 and JCA support |
| Robolectric | 4.16 | MIT; JVM Android framework simulation, tests only |
| Kotlin Coroutines Test | 1.10.2 | Apache-2.0; ViewModel dispatcher control, tests only |
| JUnit | 4.13.2 | EPL-1.0; tests only |
| Hamcrest Core | 1.3 (JUnit transitive) | BSD-3-Clause; tests only |

AndroidX, Kotlin standard library, JetBrains annotations and JSpecify transitives use their published public Maven metadata. The build resolves only Google Maven, Maven Central and the public Gradle Plugin Portal. Pinned tool and library versions are visible in the Gradle scripts; the application has no runtime network permission.

Robolectric resolves its public Android framework test artifacts from Maven Central. These test dependencies are not packaged into the application.

License texts shipped inside third-party JAR/AAR files remain attached to their respective artifacts. The Bouncy Castle notice is also retained under `licenses/BOUNCY-CASTLE.txt`.

Primary implementation references:

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

The chosen library set stays within the verified AGP 8.13.2 / API 36 toolchain. Newer-version lint notices do not change these pins automatically. Bouncy Castle includes a TLS helper that Android lint identifies as a permissive trust manager; this app neither invokes that helper nor requests network access. Local card signatures are verified explicitly and are not presented as certificate-chain trust.
