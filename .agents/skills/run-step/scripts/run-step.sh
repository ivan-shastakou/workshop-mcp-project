#!/usr/bin/env bash
# Checks out a workshop branch and (re)starts the Docker stack.
#
#   run-step.sh <branch> [--chat-only] [--fresh] [--no-checkout]
#
#   <branch>       raw | step1 … step5 | master | full branch name (e.g. step3-chat-logging)
#   --chat-only    run only the chat (profile "chat": chat-ui, chat-service, auth-service, chat-db, auth-db)
#   --fresh        delete chat-db data first (chat history, MCP servers); accounts and bookings stay
#   --no-checkout  run whatever is checked out now
#
set -uo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }

BRANCH="" CHAT_ONLY=0 FRESH=0 CHECKOUT=1
for arg in "$@"; do
  case "$arg" in
    --chat-only) CHAT_ONLY=1 ;;
    --fresh) FRESH=1 ;;
    --no-checkout) CHECKOUT=0 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    -*) die "unknown option $arg" ;;
    *) BRANCH="$arg" ;;
  esac
done

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "not inside the workshop git repository"
cd "$ROOT" || exit 1

# --- resolve and check out the branch ------------------------------------------------------------
if [ "$CHECKOUT" = 1 ]; then
  [ -n "$BRANCH" ] || die "branch is required (raw, step1 … step5, master)"
  if ! git show-ref --verify --quiet "refs/heads/$BRANCH"; then
    matches=$(git for-each-ref --format='%(refname:short)' "refs/heads/${BRANCH}-*")
    [ "$(printf '%s\n' "$matches" | grep -c .)" = 1 ] || die "unknown branch '$BRANCH'. Available: $(git for-each-ref --format='%(refname:short)' refs/heads | tr '\n' ' ')"
    BRANCH=$matches
  fi
  if [ "$(git rev-parse --abbrev-ref HEAD)" != "$BRANCH" ]; then
    [ -z "$(git status --porcelain --untracked-files=no)" ] || die "uncommitted changes to tracked files; commit or stash them before switching to $BRANCH:
$(git status --short --untracked-files=no)"
    info "Checking out $BRANCH"
    git checkout -q "$BRANCH" || die "git checkout $BRANCH failed"
  fi
fi
BRANCH=$(git rev-parse --abbrev-ref HEAD)

# --- .env ------------------------------------------------------------------------------------------
if [ ! -f .env ]; then
  cp .env.example .env
  info "Created .env from .env.example (set GEMINI_API_KEY in it for step1 and later)"
fi

# --- start the stack -------------------------------------------------------------------------------
PROJECT=${COMPOSE_PROJECT_NAME:-$(basename "$ROOT" | tr '[:upper:]' '[:lower:]')}
if [ "$CHAT_ONLY" = 1 ]; then
  PROFILE=chat
  # services of the full profile would keep running otherwise
  if docker compose --profile full ps -q backend frontend postgres 2>/dev/null | grep -q .; then
    info "Stopping the full stack (data is kept)"
    docker compose --profile full down >/dev/null 2>&1
  fi
else
  PROFILE=full
fi
COMPOSE=(docker compose --profile "$PROFILE")

if [ "$FRESH" = 1 ]; then
  info "Deleting chat-db data (chat history, MCP servers)"
  "${COMPOSE[@]}" rm -sf chat-service chat-db >/dev/null 2>&1
  docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.volume=chatdata" \
    | xargs -r docker volume rm >/dev/null
fi

info "Building and starting profile '$PROFILE' on $BRANCH (first build can take a few minutes)"
LOG=$(mktemp)
if ! "${COMPOSE[@]}" up -d --build >"$LOG" 2>&1; then
  tail -30 "$LOG" >&2
  die "docker compose up failed"
fi
rm -f "$LOG"

# --- wait until everything is healthy --------------------------------------------------------------
ENDPOINTS=("auth-service|http://localhost:8081/actuator/health" "chat-service|http://localhost:8082/actuator/health" "chat-ui|http://localhost:3001/")
[ "$PROFILE" = full ] && ENDPOINTS+=("backend|http://localhost:8080/actuator/health" "frontend|http://localhost:3000/")
info "Waiting for services"
deadline=$(( $(date +%s) + 300 ))
for entry in "${ENDPOINTS[@]}"; do
  name=${entry%%|*} url=${entry#*|}
  until curl -sf -o /dev/null "$url"; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      "${COMPOSE[@]}" ps
      echo "--- last log lines of $name:" >&2
      "${COMPOSE[@]}" logs --tail 40 "$name" >&2
      die "$name did not become ready ($url)"
    fi
    sleep 3
  done
done

# --- report ----------------------------------------------------------------------------------------
echo
info "Branch $BRANCH ($(git rev-parse --short HEAD)), profile $PROFILE"
"${COMPOSE[@]}" ps --format '{{.Service}}: {{.Status}}' | sed 's/^/  /'
echo
[ "$PROFILE" = full ] && echo "  Booking UI:      http://localhost:3000"
echo "  Standalone chat: http://localhost:3001"
echo "  Hard-refresh the browser (Cmd+Shift+R) after switching branches."
exit 0
