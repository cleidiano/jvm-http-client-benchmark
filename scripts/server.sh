#!/usr/bin/env bash
# Start/stop the nginx benchmark target in Docker, pinned to its own CPUs.
#
#   scripts/server.sh start      # fails if a server is already running
#   scripts/server.sh restart    # stops any running server, then starts with the current env
#   scripts/server.sh stop|status|logs
#   scripts/server.sh uri        # base URI to benchmark against (bench.sh uses it automatically)
#   scripts/server.sh config     # "network=<host|bridge> latency=<value|none>" of the running server
#
# Env:
#   NETWORK=host     (default) client and nginx talk over loopback: MTU 64K, ~zero RTT.
#                    Answers "how much CPU/allocation does the client spend per request?"
#   NETWORK=bridge   client talks to the container IP through docker0 + veth: MTU 1500.
#   LATENCY=500us    (bridge only) extra round-trip time added with tc netem on the
#                    container's egress (responses and ACKs). Answers "how does the client
#                    behave with real latency and concurrency?"
#   SERVER_CPUS (default 1,3)  NGINX_IMAGE (default nginx:1.30.6-alpine)  PORT (default 8080)
set -euo pipefail
cd "$(dirname "$0")/.."

NAME=hcb-nginx
IMAGE=${NGINX_IMAGE:-nginx:1.30.6-alpine}
NETEM_IMAGE=hcb-netem:1
CPUS=${SERVER_CPUS:-1,3}
PORT=${PORT:-8080}
NETWORK=${NETWORK:-host}
LATENCY=${LATENCY:-}

running() { [[ -n $(docker ps -q --filter "name=^${NAME}$") ]]; }

label() { docker inspect -f "{{index .Config.Labels \"$1\"}}" "$NAME"; }

config() { echo "network=$(label hcb.network) latency=$(label hcb.latency)"; }

container_ip() {
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$NAME"
}

uri() {
  if [[ $(label hcb.network) == host ]]; then
    echo "http://127.0.0.1:$PORT"
  else
    echo "http://$(container_ip):$PORT"
  fi
}

tc_in_container() {
  if [[ -z $(docker images -q "$NETEM_IMAGE") ]]; then
    docker build -q -t "$NETEM_IMAGE" server/netem >/dev/null
  fi
  docker run --rm --network "container:$NAME" --cap-add NET_ADMIN "$NETEM_IMAGE" "$@"
}

stop() {
  # rm -f returns only once the container is gone (stop + --rm removes it asynchronously).
  # The netem qdisc lives in the container's network namespace and disappears with it.
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}

start() {
  if running; then
    echo "$NAME already running ($(config)); use 'restart' or 'stop' first" >&2
    exit 1
  fi
  case "$NETWORK" in
    host | bridge) ;;
    *) echo "NETWORK must be host or bridge" >&2; exit 2 ;;
  esac
  if [[ -n $LATENCY && $NETWORK != bridge ]]; then
    echo "LATENCY requires NETWORK=bridge (netem on loopback would slow down the whole host)" >&2
    exit 2
  fi

  docker run -d --rm --name "$NAME" \
    --network "$NETWORK" \
    --cpuset-cpus "$CPUS" \
    --label hcb.network="$NETWORK" \
    --label hcb.latency="${LATENCY:-none}" \
    -v "$PWD/server/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
    -v "$PWD/server/nginx/10-payloads.sh:/docker-entrypoint.d/10-payloads.sh:ro" \
    "$IMAGE" >/dev/null

  if [[ -n $LATENCY ]]; then
    # High packet limit: the default (1000) can drop packets with large bodies in flight.
    if ! tc_in_container qdisc add dev eth0 root netem delay "$LATENCY" limit 100000; then
      echo "failed to apply netem; stopping server" >&2
      stop
      exit 1
    fi
  fi

  local base
  base=$(uri)
  for _ in $(seq 1 50); do
    if curl -fsS "$base/health" >/dev/null 2>&1; then
      echo "nginx up at $base ($(config) cpus=$CPUS)"
      return 0
    fi
    sleep 0.2
  done
  echo "nginx did not become healthy" >&2
  docker logs "$NAME" >&2 || true
  stop
  exit 1
}

case "${1:-}" in
  start)
    start
    ;;
  restart)
    stop
    start
    ;;
  stop)
    stop
    echo "nginx stopped"
    ;;
  status)
    if ! running; then
      echo "not running"
      exit 1
    fi
    echo "uri:     $(uri)"
    echo "config:  $(config)"
    echo "cpus:    $(docker inspect -f '{{.HostConfig.CpusetCpus}}' "$NAME")"
    if [[ $(label hcb.network) != host ]]; then
      echo "qdisc:   $(tc_in_container qdisc show dev eth0 | head -1)"
    fi
    ;;
  uri)
    running || { echo "$NAME is not running" >&2; exit 1; }
    uri
    ;;
  config)
    running || { echo "$NAME is not running" >&2; exit 1; }
    config
    ;;
  logs)
    docker logs -f "$NAME"
    ;;
  *)
    echo "usage: $0 start|restart|stop|status|uri|config|logs" >&2
    exit 2
    ;;
esac
