#!/bin/sh
# EN07.4 — thin wrapper around edoburu/pgbouncer's own /entrypoint.sh.
#
# That image's entrypoint
# (https://github.com/edoburu/docker-pgbouncer/blob/master/entrypoint.sh) reads the backend
# password from the DB_PASSWORD environment variable directly — unlike the official postgres
# image (already used by the `postgres` service in this file, via POSTGRES_PASSWORD_FILE), it
# has no "_FILE" indirection for Docker secrets. Docker/Compose secrets are always mounted as
# files under /run/secrets, never injected as env vars automatically — this script bridges
# that gap: read the secret file once at container start, export it as DB_PASSWORD for the one
# process that needs it, then hand off to the image's real entrypoint. The plaintext password
# never appears in `docker inspect`, this compose file, or any committed .env — only briefly in
# this container's own process environment, the same posture as every other secret consumer in
# this stack (see application-prod.yml's configtree import for the Spring/pivot-core side, and
# docs/deployment/secret-management.md).
#
# Reuses the SAME `postgres_password` Docker secret already declared for the `postgres` service
# — no new secret to provision or rotate. pgbouncer connects upstream to Postgres as the same
# `pivot` role pivot-core itself uses (see DB_USER in docker-compose.prod.yml).
set -eu

export DB_PASSWORD
DB_PASSWORD="$(cat "${DB_PASSWORD_FILE:?DB_PASSWORD_FILE required — path to the mounted postgres_password Docker secret}")"

exec /entrypoint.sh "$@"
