# Android Plexo theme and shared native components

This lane implements presentation for the authorized complete Android Plexo package on default `1d5f46a`. It changes no provider identity, card operation, network, secret lifecycle, permission, file ownership or result qualification policy. Root owns Issue35, publication and the integrated gate.

## Public contract and actual callers

`CardKeyTheme(content: @Composable () -> Unit)` retains its package and public API. The shell owner removes the old definition from `ManagerScreen.kt`; this lane owns the new `CardKeyTheme.kt`. Manager and provider remain actual callers. System light/dark selection remains native. Fixed scoped Plexo colors replace wallpaper-derived dynamic colors; there are no font downloads, remote assets, fixture values or hardcoded product names.

`PlexoSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)` supplies an 8 dp Material surface, wrapping native heading semantics, 16 dp padding and a non-scrolling Column. Enrollment preparation/request/issuance/import, TLS identity/service/result, provider context and shell detail sections are confirmed consumers. Each caller retains its existing scroll, inputs, warnings, actions and callback ownership.

`PlexoFactRow(label: String, value: String, modifier: Modifier = Modifier)` places the wrapping label above the wrapping value. Label/value semantics are grouped for TalkBack. The shell report, captured enrollment facts, TLS facts and verified provider RP/caller are confirmed consumers. Only the value uses native `SelectionContainer`, preserving the original report rows’ full public/technical text copyability; labels remain outside selection. The component does not reveal secret material, interpret the value, clip it or invent qualification. The original caller still controls whether a value may be displayed and retains separate secret reveal/export conditions.

## Palette, typography and native targets

Neutral white/gray and graphite surfaces follow the complete Android layout proposal. Amber primary and selected containers use distinct native on-colors. Teal is a palette role, not proof of authentication. Errors retain Material error/on-error roles. Transparent surface tint prevents elevated neutral surfaces from acquiring an unrelated color wash.

Static sRGB contrast calculations for the assigned solid color pairs:

| Role | Light minimum | Dark minimum |
| --- | ---: | ---: |
| Primary action text | 5.73:1 | 8.12:1 |
| Secondary text across assigned surface/container range | 4.60:1 | 6.02:1 |
| Solid control outline against assigned surfaces | 3.42:1 | 3.44:1 |

Plexo source amber is retained; light on-primary uses dark `#1b1408` rather than insufficient-contrast white. Muted text and control outlines are strengthened where source separators cannot serve as readable text or control boundaries. `outlineVariant` remains a subtle decorative separator, not a substitute for `outline`. Disabled Material content uses native disabled behavior; static enabled-pair calculations do not qualify every rendered component, state or focus indicator.

All five Material shape roles use 8 dp corners. Native Material typography retains platform font family, weight, letter spacing and scaling. Body-small and smaller label roles have a 14 sp / 20 sp line-height floor for Chinese readability; larger roles retain their native scale. No custom font or density override is installed. `LocalMinimumInteractiveComponentSize` is explicitly 48 dp. Shared sections/facts are noninteractive; custom clickable rows remain responsible for their own 48 dp native target, as do the shell's navigation and object components.

## Copy and activation record

Plain-language review applied to this documentation. No interface string is introduced or rewritten by these files: section titles and fact labels/values are supplied by their existing caller. Screen/key: manager/provider/CA/TLS shared theme; before: dynamic wallpaper palette and prior local theme function; after: scoped neutral Plexo Material theme with the same `CardKeyTheme(content)` entry point. Reason: implement the authorized design consistently without changing operational authority. Activation: integrated Android UI candidate only, not deployment or device acceptance. Caller-specific Chinese copy records remain with the shell, CA and provider owners; Root consolidates [CardKey #35](https://github.com/qianxueye/cardkey/issues/35).

## Verification and limits

Source inspection confirmed the installed Material 3 1.4.0 artifact exposes the minimum interactive-size composition local. Static contrast calculations and owned-path whitespace checks passed. This lane ran no Gradle build, application, device, card, API or push operation. Theme and component compilation plus meaningful behavior checks belong to the one integrated gate after all source owners freeze.

Native 360 dp/wide/landscape, light/dark, large Chinese text, long facts, TalkBack heading/fact order, real IME, native controls and secure-window acceptance remain required. Neither color contrast arithmetic nor source wiring establishes installed-device or physical-card/system-consumer acceptance.
