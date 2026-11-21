#!/usr/bin/env bash
# Headless console sign-in: walks the same OIDC authorization-code flow a browser does (console
# login -> mock IdP login form -> callback -> session cookie) and checks /api/user. Lets a terminal
# or an agent verify OIDC against either workbench mode without a browser.
#
# Internal to `make pulumi-workbench cmd=oidc-smoke`, which supplies the variables below.
set -euo pipefail

: "${CONSOLE_URL:?}" "${OIDC_ISSUER:?}" "${SUBJECT:?}" "${ORG:?}"

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
jar="$work/cookies"

fail() {
  echo "oidc-smoke: FAIL — $*" >&2
  exit 1
}

# One request, no redirect following: prints "<status> <Location>".
hop() {
  curl -sS -o "$work/body" -b "$jar" -c "$jar" --max-time 15 -w '%{http_code} %{redirect_url}\n' "$@"
}

read -r status next < <(hop "$CONSOLE_URL/api/auth/login") || fail "console unreachable at $CONSOLE_URL"
[[ $status == 30? && $next == "$CONSOLE_URL"/api/auth/flow/* ]] || fail "login: expected redirect to the auth flow, got $status $next"

read -r status authorize < <(hop "$next")
[[ $status == 30? && $authorize == "$OIDC_ISSUER"/authorize* ]] ||
  fail "flow: expected redirect to $OIDC_ISSUER/authorize, got $status $authorize"

read -r status callback < <(hop --data-urlencode "username=$SUBJECT" --data-urlencode "claims={\"email\":\"$SUBJECT@example.com\"}" "$authorize")
[[ $status == 30? && $callback == "$CONSOLE_URL"/api/auth/callback/* ]] ||
  fail "IdP login: expected redirect to the console callback, got $status $callback"

# The app redeems the code with the IdP server-side here; a failure is a 401/500, not a redirect.
read -r status landing < <(hop "$callback")
[[ $status == 30? ]] || fail "callback: expected a redirect after the code exchange, got $status: $(head -c 300 "$work/body")"

curl -fsS -b "$jar" --max-time 15 "$CONSOLE_URL/api/user" >"$work/user" || fail "/api/user rejected the new session"
grep -q "\"sub\":\"$SUBJECT\"" "$work/user" || fail "/api/user is not $SUBJECT: $(cat "$work/user")"
grep -q "\"iss\":\"$OIDC_ISSUER\"" "$work/user" || fail "/api/user issuer is not $OIDC_ISSUER: $(cat "$work/user")"
grep -q "\"name\":\"$ORG\"" "$work/user" || fail "/api/user has no membership in $ORG: $(cat "$work/user")"
if [[ $SUBJECT == admin ]]; then
  grep -q '"siteAdmin":true' "$work/user" || fail "admin is not a site admin: $(cat "$work/user")"
fi

echo "oidc-smoke: OK — $SUBJECT signed in at $CONSOLE_URL via $OIDC_ISSUER (landed on ${landing:-/})"
cat "$work/user"
echo
