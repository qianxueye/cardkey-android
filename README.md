# CardKey — Android management app

Status: awaiting Owner approval for implementation.

Self-contained Kotlin/Compose application managing both FIDO2 and PIV through qualified NFC/USB paths; Android 14+ credential-provider integration.

Visibility: Private during planning; only the Android application is eligible for a later public release.

The project baseline is J3R180, Java Card 3.0.5 and GlobalPlatform 2.3. This repository currently contains its scope and development rules; runtime implementation begins after project approval.

Dependencies and acceptance:

- All normal build dependencies must be publicly available when this repository is made public.
- Include its own standard protocol implementation and Android reader adapters.
- Never consume private core, applet CAPs, Apple modules or private CI artifacts.

[Approval plan](https://github.com/qianxueye/cardkey/blob/master/PLAN.zh-CN.md) defines the milestones and supported workflows. Repository separation does not define PR boundaries; each PR delivers one self-contained behavior with relevant validation.

The proposed license for original Android code is Apache-2.0, subject to the approved dependency inventory. Public builds must work from an isolated checkout with public dependencies and synthetic fixtures. Private project links and planning rules will be reconciled before publication.
