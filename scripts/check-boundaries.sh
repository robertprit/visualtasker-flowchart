#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
command -v rg >/dev/null || { echo "ripgrep is required" >&2; exit 1; }
pure_modules=(flowchart-domain flowchart-validation flowchart-layout flowchart-interaction flowchart-serialization flowchart-test-support)

for module in "${pure_modules[@]}"; do
  if rg -n 'com\.android\.(application|library)|org\.jetbrains\.kotlin\.android' "$module/build.gradle.kts"; then
    echo "Android plugin found in pure module $module" >&2
    exit 1
  fi
  if rg -n '^(import android\.|import androidx\.|import androidx\.compose\.)' "$module/src" 2>/dev/null; then
    echo "Android import found in pure module $module" >&2
    exit 1
  fi
done

forbidden='com\.phuntasker|EMScript|Emscript|WorkflowDomain|Blockly|Blockeditor|SharedPreferences|android\.content\.Context|onScriptChange|onCompileScript|onSaveScript|onGenerateEmscript|onWorkflowMutated|onAuthorityChanged|ReverseCompiler|reverseCompiler'
if rg -n "$forbidden" --glob '*.kt' --glob '!**/build/**' .; then
  echo "Forbidden producer, host, persistence, or reverse-compiler symbol found" >&2
  exit 1
fi

if rg -n 'project\(".*VisualTasker|project\(".*studio-|com\.phuntasker' --glob '*.gradle.kts' .; then
  echo "Studio dependency found" >&2
  exit 1
fi

echo "Dependency boundaries verified"
