#!/usr/bin/env bash
#
# Send a message to the Goose Agent Chat app and stream the response.
#
# Auth model: the app logs users in via Keycloak SSO (federated to Google/GitHub),
# so there is no headless username/password flow — a real login must happen once in
# a browser. This script does NOT log in; it reuses an already-authenticated session
# by carrying its cookies. Get those cookies one of two ways:
#
#   --from-playwright SESSION   Extract cookies from a running playwright-cli browser
#                               session that is already logged in (recommended).
#   --cookie "STR"              Pass a cookie string explicitly, e.g.
#                               "JSESSIONID=...; __VCAP_ID__=..." (both matter — the
#                               __VCAP_ID__ sticky-routing cookie pins the CF instance
#                               that holds your server-side session).
#
# Once a working cookie is captured it is cached (/tmp/goose-chat-cookie.txt, mode 600)
# and reused until it stops authenticating, at which point you re-login in the browser.
#
# Usage:
#   goose-chat-helper.sh --from-playwright brokertest "List 3 of my GitHub repos"
#   goose-chat-helper.sh --cookie "JSESSIONID=...; __VCAP_ID__=..." "your message"
#   goose-chat-helper.sh "your message"            # uses cached cookie + session
#   goose-chat-helper.sh --session chat-abc123 "follow-up message"
#
set -uo pipefail

APP_URL="https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com"
COOKIE_CACHE="/tmp/goose-chat-cookie.txt"
SESSION_FILE="/tmp/goose-chat-session.txt"
URL_CACHE="/tmp/goose-chat-url.txt"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

PW_SESSION=""; COOKIE=""; SESSION_OVERRIDE=""; MESSAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --url) APP_URL="$2"; echo "$2" > "$URL_CACHE"; shift 2;;
    --from-playwright|--pw) PW_SESSION="$2"; shift 2;;
    --cookie) COOKIE="$2"; shift 2;;
    --session) SESSION_OVERRIDE="$2"; shift 2;;
    -*) echo "unknown arg: $1" >&2; exit 2;;
    *) MESSAGE="$1"; shift;;
  esac
done
[ -f "$URL_CACHE" ] && [ -z "${APP_URL_SET:-}" ] && APP_URL="$(cat "$URL_CACHE" 2>/dev/null || echo "$APP_URL")"
HOST="$(printf '%s' "$APP_URL" | sed -E 's#https?://([^/]+).*#\1#')"

# --- Resolve a cookie string: explicit > playwright extraction > cache ---
if [ -n "$PW_SESSION" ]; then
  COOKIE="$(playwright-cli -s="$PW_SESSION" cookie-list --domain="$HOST" 2>/dev/null \
    | grep -oE '^[^ ]+=[^ ]+' | paste -sd ';' -)"
  [ -n "$COOKIE" ] || { echo -e "${RED}Could not read cookies from playwright session '$PW_SESSION'. Is it open and logged in?${NC}" >&2; exit 1; }
fi
[ -z "$COOKIE" ] && [ -f "$COOKIE_CACHE" ] && COOKIE="$(cat "$COOKIE_CACHE")"

if [ -z "$COOKIE" ]; then
  cat >&2 <<EOF
${RED}No session cookie available.${NC} The app uses Keycloak SSO (Google/GitHub federation),
so log in once in a browser, then hand this script the cookies. For example, with playwright-cli:

  playwright-cli -s=goose open "$APP_URL" --headed
  # complete the SSO login (Google) in the window, then:
  $(basename "$0") --from-playwright goose "your message"
EOF
  exit 1
fi

auth_check() { curl -sk --max-time 20 -H "Cookie: $COOKIE" "$APP_URL/auth/status" 2>/dev/null | grep -q '"authenticated":true'; }

if ! auth_check; then
  echo -e "${RED}The provided cookie is not authenticated${NC} (session expired or wrong instance)." >&2
  echo -e "Re-login in the browser and re-run with ${YELLOW}--from-playwright <session>${NC} (or a fresh --cookie)." >&2
  rm -f "$COOKIE_CACHE"
  exit 1
fi
# cache the working cookie
umask 077; printf '%s' "$COOKIE" > "$COOKIE_CACHE"
who="$(curl -sk --max-time 20 -H "Cookie: $COOKIE" "$APP_URL/auth/status" | grep -o '"email":"[^"]*"' | cut -d'"' -f4)"
echo -e "${GREEN}✓ Authenticated as ${who:-unknown}${NC}" >&2

# --- Session: explicit override, else reuse cached active session, else create ---
get_session() {
  local sid="$SESSION_OVERRIDE"
  [ -z "$sid" ] && [ -f "$SESSION_FILE" ] && sid="$(cat "$SESSION_FILE")"
  if [ -n "$sid" ]; then
    if curl -sk --max-time 20 -H "Cookie: $COOKIE" "$APP_URL/api/chat/sessions/${sid}/status" 2>/dev/null | grep -q '"active":true'; then
      echo -e "${GREEN}✓ Using session: $sid${NC}" >&2; echo "$sid"; return 0
    fi
    echo -e "${YELLOW}Session $sid not active; creating a new one${NC}" >&2
  fi
  local resp; resp="$(curl -sk --max-time 20 -H "Cookie: $COOKIE" -X POST "$APP_URL/api/chat/sessions" -H "Content-Type: application/json" -d '{}')"
  sid="$(printf '%s' "$resp" | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)"
  [ -n "$sid" ] || { echo -e "${RED}✗ Failed to create session: $resp${NC}" >&2; return 1; }
  echo "$sid" > "$SESSION_FILE"; echo -e "${GREEN}✓ Created session: $sid${NC}" >&2; echo "$sid"
}

send_message() {
  local sid="$1" msg="$2"
  local enc; enc="$(printf %s "$msg" | jq -sRr @uri)"
  echo -e "\n${BLUE}Goose response:${NC}" >&2
  echo -e "${BLUE}─────────────────────────────────────${NC}" >&2
  local event_type=""
  curl -sk -N --max-time 300 -H "Cookie: $COOKIE" -H "Accept: text/event-stream" \
    "$APP_URL/api/chat/sessions/${sid}/stream?message=${enc}" | while IFS= read -r line; do
    if [[ "$line" =~ ^event:(.+)$ ]]; then event_type="${BASH_REMATCH[1]}"
    elif [[ "$line" =~ ^data:(.+)$ ]]; then
      local data="${BASH_REMATCH[1]}"
      case "$event_type" in
        token)    printf "%s" "$(echo "$data" | jq -r '.' 2>/dev/null || echo "$data")";;
        complete) echo -e "\n${BLUE}─────────────────────────────────────${NC}" >&2; echo -e "${GREEN}✓ Complete (${data} tokens)${NC}" >&2;;
        error)    echo -e "\n${RED}✗ Error: $data${NC}" >&2;;
        activity)
          local t; t="$(echo "$data" | jq -r '.type // empty' 2>/dev/null)"
          if [ "$t" = "tool_request" ]; then
            echo -e "\n${YELLOW}[Tool Call: $(echo "$data" | jq -r '.extensionId // empty')/$(echo "$data" | jq -r '.toolName // empty')]${NC}" >&2
          fi;;
      esac
    fi
  done
  echo "" >&2
}

[ -n "$MESSAGE" ] || { echo -e "${YELLOW}Authenticated, but no message given. Pass a message to send.${NC}" >&2; exit 0; }
SID="$(get_session)" || exit 1
send_message "$SID" "$MESSAGE"
