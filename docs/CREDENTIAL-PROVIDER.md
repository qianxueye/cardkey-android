# Android credential provider

The provider targets Android 14 (API 34) and later. It supports interactive public-key creation and sign-in using the same public Kotlin FIDO/APDU implementation as the manager. It stores no passkeys, private keys, PIN tokens, credential index or account database on the phone. Each operation opens one card session after user confirmation.

## Android integration

`CardKeyProviderService` requires Android's `BIND_CREDENTIAL_PROVIDER_SERVICE` permission. Its create response contains one card-storage destination with auto-selection disabled. Its get response contains a generic AndroidX `Action`; it does not invent a credential entry before a card has been read. AndroidX Credentials is pinned to 1.6.0, without a Play Services compatibility adapter.

The mutable pending intents have explicit private activities, distinct request codes and unguessable data URIs. An in-memory, bounded five-minute offer binds the operation, original JSON, delegated hash, caller and selected option ID. The activity consumes that offer once and retrieves the system request with `PendingIntentHandler`. Missing, changed or ambiguous fields fail before card connection. Process death, activity recreation or duplicate callbacks cannot replay an operation. Clear-state removes transient offers for the caller; it never deletes card credentials or resets an applet.

## Calling application trust

The initial delegated-browser policy includes only Chrome Stable (`com.android.chrome`) and current signer SHA-256 fingerprint:

```text
F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83
```

Policy source: [Google privileged-app allowlist](https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json), checked 2026-10-10. `CallingAppInfo.getOrigin` must succeed. The provider also checks every current APK signer; an SDK check involving certificate history is insufficient for this policy. A failed delegated-origin check never falls back to native-app authorization.

A verified browser supplies exactly 32 bytes of client-data hash, passed unchanged to the card. The browser owns origin/RP validation, including Related Origin Requests; the provider does not impose an extra RP suffix check. Delegated responses omit `response.clientDataJSON`. AndroidX 1.6.0's attestation and assertion encoders do this when a supplied hash exists. Current Chromium's JSON readers accept the missing field, and its `CredManHelper` restores the browser's saved client data for both create and get. This source evidence does not establish every released Chrome version or a successful account operation.

Native apps must have one current APK signer and must not supply a client-data hash. The provider fetches only `https://<rpId>/.well-known/assetlinks.json`. A single statement must contain `delegate_permission/common.handle_all_urls` and an `android_app` target with the exact package and current signing-certificate fingerprint. `get_login_creds` alone does not authorize the request. Fields from different statements are never combined. Includes are not followed.

DAL uses a dedicated OkHttp client with Android's default TLS trust and hostname validation. Redirects, cookies, authentication, retries and caching are disabled. It requires HTTP 200, JSON content, valid UTF-8, a 256 KiB limit and a five-second total call timeout. Failure is terminal. Native client data contains the exact type, canonical challenge, `android:apk-key-hash:<base64url SHA256 certificate>` origin and `crossOrigin:false`; one serialization supplies both returned bytes and the hash signed by the card.

## Card behavior and privacy

The provider supports ES256, discoverable and non-discoverable credentials, allow/exclude lists, required/preferred/discouraged resident keys and user verification. Unsupported required behavior is rejected before the credential command. Optional extensions are not requested and no client-extension result is claimed. Enterprise attestation is unsupported in the provider; manager configuration is a separate workflow.

Registration verifies RP binding, user presence, required UV, the ES256 credential key, exclusions and any supplied attestation signature before encoding a response. For `none` and `indirect`, the response uses `fmt=none`, an empty statement and a zero AAGUID; other authenticator-data bytes remain unchanged. `direct` preserves the validated card response. A valid original `none` response has no attestation signature. Certificate signature verification does not establish certificate trust.

Assertion validation checks RP binding, required UP/UV, credential IDs, user handles, authenticator-data structure and ES256 DER signature shape. Authenticator data and signature bytes are returned unchanged. The RP verifies the signature using its registered public key; the provider cannot establish account acceptance from a stateless assertion alone. GetAssertion and every GetNextAssertion remain contiguous in one card session. Multiple results remain transient while the user selects an account. CardKey rejects backup-eligible/backed-up flags because this provider's credentials are hardware-bound.

The provider and manager share an exclusive transport lease, including card-removal probes. PIN authorization is scoped to the operation and cleared afterward. PIN verification does not satisfy physical presence. No error path resets, power-cycles or reconnects the card to manufacture presence. Lost creation responses remain uncertain and are never recreated automatically. Cancellation does not undo a command already sent.

## Verification and acceptance

Unit tests cover strict parsing, caller binding, DAL policy and transport failures, native byte/hash equality, attestation privacy, unsupported requirements, RP/credential/UV/backup flags, nonresident allow lists, contiguous account results, exclusive sessions and prevention of replay. Android instrumentation uses a scripted card through the normal connection boundary and the real Android request/response parcel APIs. The fixture RP independently verifies returned signatures.

Run software checks:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

Root coordinates installation and instrumentation execution. Scripted Android plumbing, the system provider picker, live native DAL, Chrome Stable, real NFC/USB interaction and external account acceptance are separate evidence. No physical device/card/account acceptance is claimed by a successful build.

## Primary references

- [Android credential-provider integration](https://developer.android.com/identity/sign-in/credential-provider)
- [AndroidX Action contract](https://developer.android.com/reference/androidx/credentials/provider/Action)
- [CallingAppInfo trust contract](https://developer.android.com/reference/androidx/credentials/provider/CallingAppInfo)
- [AndroidX 1.6.0 source archive](https://dl.google.com/dl/android/maven2/androidx/credentials/credentials/1.6.0/credentials-1.6.0-sources.jar)
- [Chromium credential response parsing](https://github.com/chromium/chromium/blob/main/components/webauthn/json/value_conversions.cc)
- [Chromium CredManHelper](https://github.com/chromium/chromium/blob/main/components/webauthn/android/java/src/org/chromium/components/webauthn/cred_man/CredManHelper.java)
- [Google seamless credential sharing prerequisites](https://codelabs.developers.google.com/seamless-credential-sharing)
- [WebAuthn relying-party operations](https://www.w3.org/TR/webauthn-3/#sctn-rp-operations)
