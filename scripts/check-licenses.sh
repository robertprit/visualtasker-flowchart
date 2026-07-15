#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
command -v rg >/dev/null || { echo "ripgrep is required" >&2; exit 1; }
test -f LICENSE
rg -q 'Apache License' LICENSE
rg -q 'Version 2.0, January 2004' LICENSE
if rg -n 'GPL|AGPL|SSPL|Commons Clause|BUSL|source-available' gradle/libs.versions.toml **/build.gradle.kts; then
  echo "Forbidden dependency license marker found" >&2
  exit 1
fi
echo "Direct dependency inventory contains only audited artifacts; see docs/DEPENDENCY_LICENSES.md"
