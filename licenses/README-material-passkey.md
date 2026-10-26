# Material passkey asset

`app/src/main/res/drawable/ic_approved_passkey.xml` preserves the approved Google Material Symbols passkey geometry. Native import changes only the coordinate transform: translate Y by 24, scale X/Y by 0.025, and use the surrounding icon tint. The displayed size remains at least 24dp.

Upstream repository: `google/material-design-icons`.
Pinned commit: `6d7ca43bd6e6668531a00fcaca06d921b63dd716` (Update Symbols 2024-05-04).

Immutable source: https://github.com/google/material-design-icons/blob/6d7ca43bd6e6668531a00fcaca06d921b63dd716/symbols/web/passkey/materialsymbolsoutlined/passkey_24px.svg

The approved Google Fonts delivery and pinned repository SVG use a different start point and shorthand sequence for the key-hole contour. Comparing every oriented absolute line/quadratic segment, ignoring only contour starting order, gives the same 41 segments and matching viewBox. The product path remains unchanged.

License: Apache License 2.0, retained in `LICENSE-material-passkey.txt`.
Immutable license: https://github.com/google/material-design-icons/blob/6d7ca43bd6e6668531a00fcaca06d921b63dd716/LICENSE

The APK includes provenance in `assets/licenses/MATERIAL-PASSKEY.txt` and the existing full Apache license in `assets/licenses/APACHE-2.0.txt`.
