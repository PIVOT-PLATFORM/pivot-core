#!/bin/sh
# EN07.4 — basic PgBouncer connection-leak / pool-sizing check ("Tests de charge basiques
# (vérification pas de connexion leak)" AC).
#
# What this proves, and why session mode still needs it: PgBouncer session pooling assigns a
# real Postgres backend connection to a client for the client's *entire* connection lifetime
# (not per-transaction, unlike transaction-mode pooling) — this is exactly why it's the only
# mode compatible with JPA/Hibernate prepared statements and long transactions (this
# Enabler's own AC). The trade-off: a session-mode pool only bounds the number of REAL
# Postgres connections if clients actually connect/disconnect rather than holding one
# connection open forever. This script drives many more concurrent short-lived connections
# than DEFAULT_POOL_SIZE through pgbouncer (`pgbench -C`: reconnect on every transaction,
# simulating Hikari borrow/return churn across the 4 Spring Boot services this Enabler exists
# to protect — see docker-compose.prod.yml's pgbouncer service comment) and checks, directly
# against Postgres (bypassing pgbouncer), that:
#   1. real backend connections never exceed DEFAULT_POOL_SIZE, even with 2x that many
#      concurrent pgbench clients hammering pgbouncer at once (excess clients queue at the
#      pgbouncer layer instead of Postgres hard-refusing them past max_connections — the
#      whole point of this Enabler);
#   2. real backend connections drop back to baseline once the load stops (no leak — a
#      leaked/stuck server connection would keep counting against the pool forever).
#
# Does NOT touch the target database's schema (no pgbench_accounts/-branches/-tellers/
# -history tables created) — runs a trivial custom query script (`pgbench -f`) instead of the
# standard TPC-B-like workload, safe to run against a real dev/shared database.
#
# What "no leak" actually means here — deliberately NOT "backend connection count returns to
# 0": PgBouncer keeps server connections open and IDLE in the pool after clients disconnect
# (governed by `server_idle_timeout`, default 600s) so the next burst doesn't pay a fresh
# connection-setup cost — that's the intended behaviour of a pooler, not a leak. A real leak
# looks like: a server connection stuck in `sv_active` (still considered "checked out to a
# client") long after every pgbench client has disconnected, or the idle+active total growing
# unboundedly across repeated bursts instead of settling at/under DEFAULT_POOL_SIZE. This
# script therefore asserts on `sv_active` from PgBouncer's own `SHOW POOLS` (must drop to 0
# once load stops), not on raw `pg_stat_activity` count (which is expected to stay at/near the
# peak — that's pooling working as designed).
#
# Usage (from the repo root, dev stack already up via `docker compose up -d postgres redis
# pgbouncer pgbouncer-exporter`):
#
#   docker compose run --rm --network pivot-core_default \
#     -e PGPASSWORD=${POSTGRES_PASSWORD:-pivot_dev} \
#     -v "$(pwd)/docker/pgbouncer/loadtest.sh:/loadtest.sh:ro" \
#     --entrypoint /loadtest.sh postgres:18-alpine
#
# (Network name depends on the compose project name — `docker compose ps` or `docker network
# ls` to confirm; defaults to `<project-dir-name>_default` for the dev compose.yml's implicit
# default network.)
set -eu

PGHOST="${PGHOST:-pgbouncer}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-pivot_dev}"
PGUSER="${PGUSER:-pivot}"
export PGPASSWORD="${PGPASSWORD:?PGPASSWORD required (dev default: pivot_dev)}"
DIRECT_PGHOST="${DIRECT_PGHOST:-postgres}"
DIRECT_PGPORT="${DIRECT_PGPORT:-5432}"
CLIENTS="${CLIENTS:-40}"
DURATION="${DURATION:-15}"
# Must match DEFAULT_POOL_SIZE on the pgbouncer service (compose.yml / docker-compose.prod.yml)
# — kept as a parameter here rather than hardcoded so the two never silently drift apart.
DEFAULT_POOL_SIZE="${DEFAULT_POOL_SIZE:-20}"

ADMIN_DSN="postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/pgbouncer?sslmode=disable"
DIRECT_DSN="postgresql://${PGUSER}:${PGPASSWORD}@${DIRECT_PGHOST}:${DIRECT_PGPORT}/${PGDATABASE}?sslmode=disable"

backend_count() {
  psql "$DIRECT_DSN" -tAc \
    "SELECT count(*) FROM pg_stat_activity WHERE usename = '${PGUSER}' AND datname = '${PGDATABASE}' AND pid <> pg_backend_pid();"
}

# sv_active from PgBouncer's own admin console for the (PGDATABASE, PGUSER) pool — the real
# "is a server connection still checked out to a client" signal (see header comment above for
# why raw backend_count is NOT the right leak signal).
sv_active() {
  psql "$ADMIN_DSN" -tA -F',' -c "SHOW POOLS;" \
    | awk -F',' -v db="$PGDATABASE" -v usr="$PGUSER" '$1==db && $2==usr {print $7}'
}

echo "=== EN07.4 load test: baseline (before load) ==="
BASELINE="$(backend_count)"
echo "Real Postgres backend connections (direct, bypassing pgbouncer): ${BASELINE}"
psql "$ADMIN_DSN" -c "SHOW POOLS;" || true

QUERY_FILE="$(mktemp)"
trap 'rm -f "$QUERY_FILE"' EXIT
printf 'SELECT pg_sleep(0.05);\n' > "$QUERY_FILE"

echo "=== EN07.4 load test: ${CLIENTS} concurrent reconnecting clients for ${DURATION}s against pgbouncer (session mode, DEFAULT_POOL_SIZE=${DEFAULT_POOL_SIZE}) ==="
pgbench -n -f "$QUERY_FILE" -C -c "$CLIENTS" -j 4 -T "$DURATION" \
  -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$PGDATABASE"

echo "=== EN07.4 load test: pool state sampled immediately after pgbench exits ==="
psql "$ADMIN_DSN" -c "SHOW POOLS;" || true
PEAK="$(backend_count)"
echo "Real Postgres backend connections right after load: ${PEAK}"

echo "=== EN07.4 load test: waiting a moment for pgbouncer to mark server connections idle ==="
sleep 5
AFTER="$(backend_count)"
AFTER_SV_ACTIVE="$(sv_active)"
echo "Real Postgres backend connections after cooldown (expected: still pooled, near PEAK — not a leak by itself): ${AFTER}"
echo "PgBouncer sv_active (server connections still checked out to a client) after cooldown: ${AFTER_SV_ACTIVE}"
psql "$ADMIN_DSN" -c "SHOW POOLS;" || true

echo "=== EN07.4 load test: assertions ==="
FAILED=0

if [ "$PEAK" -gt "$DEFAULT_POOL_SIZE" ]; then
  echo "FAIL: peak real backend connections (${PEAK}) exceeded DEFAULT_POOL_SIZE (${DEFAULT_POOL_SIZE}) — pgbouncer is not bounding connections as expected."
  FAILED=1
else
  echo "OK: peak real backend connections (${PEAK}) stayed within DEFAULT_POOL_SIZE (${DEFAULT_POOL_SIZE}) despite ${CLIENTS} concurrent pgbench clients."
fi

if [ "${AFTER_SV_ACTIVE:-0}" -gt 0 ]; then
  echo "FAIL: ${AFTER_SV_ACTIVE} server connection(s) still marked sv_active (checked out) after every pgbench client disconnected — connection leak."
  FAILED=1
else
  echo "OK: sv_active is 0 after cooldown — every server connection pgbouncer opened during the load returned cleanly to the idle pool, no leak."
fi

exit "$FAILED"
