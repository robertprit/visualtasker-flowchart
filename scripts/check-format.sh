#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if rg -n $'\t| +$' --glob '*.kt' --glob '*.kts' --glob '*.md' --glob '*.sh' --glob '!**/build/**' .; then
  echo "Tabs or trailing whitespace found" >&2
  exit 1
fi
missing="$(find . -path '*/src/*' -type f \( -name '*.kt' -o -name '*.xml' \) -not -path '*/build/*' -print0 | xargs -0 rg --files-without-match 'SPDX-License-Identifier: Apache-2.0' || true)"
if [[ -n "$missing" ]]; then
  echo "Missing SPDX headers:" >&2
  echo "$missing" >&2
  exit 1
fi
echo "Formatting and SPDX headers verified"
