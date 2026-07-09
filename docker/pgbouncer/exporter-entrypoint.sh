#!/bin/sh
# EN07.4 — same secret-file bridge as docker/pgbouncer/entrypoint.sh, for the Prometheus
# sidecar (prometheuscommunity/pgbouncer-exporter). That binary takes its target as a single
# connection-string flag/env var (PGBOUNCER_EXPORTER_CONNECTION_STRING) — no "_FILE"
# indirection either, so the password can't be handed to it as a plain compose `environment:`
# value without violating this file's "no plaintext credential" rule. This script reads the
# same `postgres_password` secret already mounted for `postgres`/`pgbouncer` (see
# docker/pgbouncer/entrypoint.sh — no separate admin credential is provisioned) and builds the
# connection string in-process, right before exec'ing the real binary.
#
# The exporter authenticates against PgBouncer's own virtual "pgbouncer" admin console
# database (SHOW POOLS / SHOW STATS — see PGBOUNCER_ADMIN_USERS in docker-compose.prod.yml)
# as the same `pivot` role already granted admin/stats rights there — PgBouncer's admin flag
# is internal to PgBouncer itself and grants no extra privilege on the real Postgres role, so
# reusing it avoids a second secret purely for metrics scraping.
set -eu

PGBOUNCER_EXPORTER_PASSWORD="$(cat "${DB_PASSWORD_FILE:?DB_PASSWORD_FILE required — path to the mounted postgres_password Docker secret}")"

exec /bin/pgbouncer_exporter \
  "--pgBouncer.connectionString=postgres://${PGBOUNCER_EXPORTER_USER:-pivot}:${PGBOUNCER_EXPORTER_PASSWORD}@${PGBOUNCER_EXPORTER_HOST:-pgbouncer}:${PGBOUNCER_EXPORTER_PORT:-5432}/pgbouncer?sslmode=disable"
