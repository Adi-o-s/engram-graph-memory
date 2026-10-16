#!/usr/bin/env bash
# Example: run your own agent flow against a local Memora, step by step.
# A "Reviewer" agent (registered in Agent workspace with an API key) receives a security review task
# from the Developer, reads shared memory, records findings and hands the task back.
#
#   REVIEWER_KEY=mem_... scripts/reviewer-flow.sh
#
# Needs curl and jq. Optional: MEMORA_URL (default http://localhost:8080), ADMIN_EMAIL, ADMIN_PASSWORD,
# PROJECT_SLUG (default atlas), STEP=1 to pause after each step.
set -euo pipefail
API="${MEMORA_URL:-http://localhost:8080}/api/v1"
: "${REVIEWER_KEY:?set REVIEWER_KEY to the key shown when you registered the agent}"
ADMIN_EMAIL="${ADMIN_EMAIL:-alice@acme.test}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-memora-demo}"
PROJECT_SLUG="${PROJECT_SLUG:-atlas}"

step() { echo; echo "=== $* ==="; if [ "${STEP:-0}" = 1 ]; then read -r -p "Enter to continue..." _; fi; }
# call METHOD PATH [JSON]  -- as the admin user;  agent METHOD PATH [JSON] -- as the Reviewer agent
call()  { curl -sS -f -X "$1" "$API$2" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' ${3:+-d "$3"}; }
agent() { curl -sS -f -X "$1" "$API$2" -H "X-Agent-Key: $REVIEWER_KEY" -H 'Content-Type: application/json' ${3:+-d "$3"}; }

step "1. Admin signs in and looks up the project and agents"
TOKEN=$(curl -sS -f "$API/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .token)
PROJECT=$(call GET /projects | jq -r --arg s "$PROJECT_SLUG" '.[] | select(.slug==$s) | .id')
DEVELOPER=$(call GET /agents | jq -r '.[] | select(.role=="DEVELOPER") | .id' | head -1)
REVIEWER=$(agent GET /agents/me | jq -r .id)
echo "project=$PROJECT developer=$DEVELOPER reviewer=$REVIEWER ($(agent GET /agents/me | jq -r .name))"

step "2. A person teaches security facts in a conversation (rule-based extraction turns them into memories)"
CONV=$(call POST /conversations "{\"title\":\"Security kickoff\",\"projectId\":\"$PROJECT\"}" | jq -r .id)
REFS=()
for text in "We use JWT for authentication." \
            "Requirement: API keys must never appear in logs." \
            "When a dependency has a known CVE, upgrade it before release."; do
  out=$(call POST "/conversations/$CONV/messages" "{\"content\":\"$text\",\"extract\":true}")
  echo "$out" | jq -r '.extracted[] | "  \(.outcome) \(.memory.type): \(.memory.content)"'
  while read -r id; do [ -n "$id" ] && REFS+=("$id"); done < <(echo "$out" | jq -r '.extracted[].memory.id')
done
REFS_JSON=$(printf '%s\n' "${REFS[@]}" | jq -R . | jq -s .)

step "3. Create the task"
TASK=$(call POST /tasks "{\"projectId\":\"$PROJECT\",\"title\":\"Security review of the login endpoint\",
  \"description\":\"Check authentication, secret handling and dependencies before release.\",\"priority\":2}")
TASK_ID=$(echo "$TASK" | jq -r .id); echo "task $TASK_ID version $(echo "$TASK" | jq .version)"

step "4. Hand the task from Developer to Reviewer (structured handoff with memory references)"
HANDOFF=$(call POST /handoffs "{\"taskId\":\"$TASK_ID\",\"fromAgentId\":\"$DEVELOPER\",\"toAgentId\":\"$REVIEWER\",
  \"summary\":\"Login endpoint is implemented. Please review it for security issues.\",
  \"decisions\":[\"Tokens are HS256 JWTs\"],\"memoryRefs\":$REFS_JSON,
  \"nextAction\":\"Review auth, secret handling and dependencies; record findings; hand back.\"}")
HANDOFF_ID=$(echo "$HANDOFF" | jq -r .id); echo "handoff $HANDOFF_ID status $(echo "$HANDOFF" | jq -r .status)"

step "5. Reviewer (with its own API key) checks its inbox and acknowledges"
agent GET "/agents/$REVIEWER/inbox" | jq -r '.[] | "  inbox: \(.status) \(.summary)"'
agent POST "/handoffs/$HANDOFF_ID/ack" | jq -r '"acknowledged, attempt \(.attempts)/\(.maxAttempts), lease until \(.leaseExpiresAt)"'

step "6. Reviewer builds its working context from shared memory"
agent POST /context "{\"taskId\":\"$TASK_ID\",\"query\":\"security authentication secrets logs dependency\",\"maxChars\":2000}" | jq -r .rendered

step "7. Reviewer records its findings as memories linked to the task"
F1=$(agent POST /memories "{\"type\":\"SEMANTIC\",\"category\":\"finding\",\"projectId\":\"$PROJECT\",\"taskId\":\"$TASK_ID\",
  \"content\":\"Security finding: login responses must not reveal whether an email exists.\",\"importance\":4,\"tags\":[\"security\",\"finding\"]}" | jq -r .memory.id)
F2=$(agent POST /memories "{\"type\":\"PROCEDURAL\",\"category\":\"lesson\",\"projectId\":\"$PROJECT\",
  \"content\":\"Before release, run a dependency CVE scan and record the result on the task.\",\"importance\":4,\"tags\":[\"security\",\"process\"]}" | jq -r .memory.id)
echo "findings $F1 $F2"

step "8. Reviewer writes its verdict into shared task state (version-checked update)"
V=$(agent GET "/tasks/$TASK_ID" | jq .version)
agent PATCH "/tasks/$TASK_ID" "{\"expectedVersion\":$V,\"status\":\"IN_PROGRESS\",\"note\":\"Security review done\",
  \"statePatch\":{\"securityReview\":{\"verdict\":\"changes-requested\",\"findings\":[\"$F1\"]}}}" | jq -r '"task now \(.status), version \(.version)"'

step "9. Reviewer completes its handoff and hands the task back to the Developer"
agent POST "/handoffs/$HANDOFF_ID/complete" '{"result":{"verdict":"changes-requested"}}' | jq -r '"handoff \(.status)"'
agent POST /handoffs "{\"taskId\":\"$TASK_ID\",\"fromAgentId\":\"$REVIEWER\",\"toAgentId\":\"$DEVELOPER\",
  \"summary\":\"Review done: one finding to fix.\",\"memoryRefs\":[\"$F1\",\"$F2\"],
  \"nextAction\":\"Fix the finding, then hand back to Reviewer.\"}" | jq -r '"new handoff to Developer: \(.status)"'

step "Done"
echo "Open the dashboard: Tasks & handoffs -> 'Security review of the login endpoint' to see the event log,"
echo "shared state and both handoffs; Memory explorer -> search 'security' to see the findings."
