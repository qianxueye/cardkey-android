#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
sips -z 432 432 branding/app-icon-master.png --out app/src/main/res/drawable-nodpi/ic_decisivecard.png >/dev/null
