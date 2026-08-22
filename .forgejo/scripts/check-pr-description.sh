#!/usr/bin/env bash
# Validate that a pull request description was actually filled in, rather than
# submitted as the untouched template.
#
# Usage: check-pr-description.sh <file containing the PR body>
#
# Deliberately checks engagement, not prose quality:
#   - every template heading is present
#   - "What this changes" has something under it
#   - the Documentation and Spec groups have a box ticked (each includes an
#     opt-out option, so there is always a correct answer)
#   - every box under Checks is ticked - those are claims the author is making
#
# Exits 1 with an actionable message listing everything wrong at once, so a
# contributor fixes the description in one pass rather than one failure at a time.

set -uo pipefail

BODY_FILE="${1:?usage: check-pr-description.sh <body-file>}"
[[ -f "$BODY_FILE" ]] || { echo "::error::no such file: $BODY_FILE"; exit 2; }

# Strip CRs so \r\n bodies do not break heading matches.
BODY=$(tr -d '\r' < "$BODY_FILE")

problems=()

if [[ -z "${BODY//[[:space:]]/}" ]]; then
  echo "::error::The pull request description is empty."
  echo
  echo "Fill in the template at .forgejo/PULL_REQUEST_TEMPLATE.md."
  exit 1
fi

# --- headings -------------------------------------------------------------
for heading in "What this changes" "Documentation" "Spec" "Checks"; do
  grep -qiE "^##[[:space:]]+${heading}[[:space:]]*$" <<< "$BODY" \
    || problems+=("Missing section heading: '## ${heading}'")
done

# --- section extraction ---------------------------------------------------
# Print the lines between one '## <name>' heading and the next '## ' heading.
section() {
  awk -v want="$1" '
    /^##[[:space:]]+/ {
      line = $0
      sub(/^##[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      inside = (tolower(line) == tolower(want))
      next
    }
    inside { print }
  ' <<< "$BODY"
}

ticked()   { grep -cE '^[[:space:]]*-[[:space:]]*\[[xX]\]' <<< "$1"; }
unticked() { grep -cE '^[[:space:]]*-[[:space:]]*\[[[:space:]]\]' <<< "$1"; }

# --- "What this changes" must say something -------------------------------
what=$(section "What this changes")
# Ignore blank lines and HTML comments left over from the template.
what_content=$(grep -vE '^[[:space:]]*$|^[[:space:]]*<!--' <<< "$what")
[[ -n "${what_content//[[:space:]]/}" ]] \
  || problems+=("'## What this changes' is empty - describe what this PR does and why.")

# --- Documentation and Spec need a choice ---------------------------------
for group in "Documentation" "Spec"; do
  body=$(section "$group")
  n=$(ticked "$body")
  total=$(( n + $(unticked "$body") ))
  if (( total == 0 )); then
    problems+=("'## ${group}' has no checkboxes - do not delete the template's options.")
  elif (( n == 0 )); then
    problems+=("'## ${group}' has no box ticked - every option list includes an opt-out, so pick one.")
  fi
done

# --- Checks are assertions; all of them ------------------------------------
checks=$(section "Checks")
left=$(unticked "$checks")
if (( left > 0 )); then
  problems+=("'## Checks' has ${left} unticked box(es) - tick each one you have actually run.")
fi

# --- report ---------------------------------------------------------------
if (( ${#problems[@]} > 0 )); then
  echo "::error::The pull request description does not follow the template."
  echo
  for p in "${problems[@]}"; do echo "  - $p"; done
  echo
  echo "Template: .forgejo/PULL_REQUEST_TEMPLATE.md"
  echo "Edit the description on the pull request page; this check re-runs on edit."
  exit 1
fi

echo "Pull request description follows the template."
