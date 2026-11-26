#!/bin/bash
# Build, test, run and develop the FIX Exchange Simulator.
#
# Everything runs inside Docker. This script needs only the docker CLI on the host:
# the tests, the admin UI build and the Java build all run in containers.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Default configuration
IMAGE_NAME="${IMAGE_NAME:-fix-simulator}"
IMAGE_PREFIX="${IMAGE_PREFIX:-}"   # optional namespace path, e.g. "team" -> team/fix-simulator
REGISTRY="${REGISTRY:-}"           # optional registry host, e.g. "registry.example.com:5000"
VERSION="${VERSION:-latest}"
PLATFORM="${PLATFORM:-linux/amd64}"
PUSH_LATEST="${PUSH_LATEST:-true}"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $*"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

usage() {
  cat <<EOF
Usage: $0 <command> [options]

Commands:
  build       Build fix-simulator image (compiles, runs the tests, builds the UI)
  run         Start the image as container fix-simulator (admin token printed)
  test        Run the Java tests and coverage report in the toolbox container
  ui-build    Build the admin UI in the toolbox container
  shell       Open a shell in the toolbox container (JDK 17, Maven, Node 20)
  dev-image   Build the toolbox image
  push        Push image to registry
  build-push  Build and push (default)
  clean       Remove local images
  help        Show this message

Environment variables for run / test:
  ADMIN_TOKEN          Admin token for run (default: a random token, printed)
  MVN_ARGS             Extra Maven arguments for test, e.g. MVN_ARGS="-Dtest=OrderTest"
  SKIP_TESTS=true      Skip tests in the build (for quick iteration only)

Options:
  --version <tag>      Image version tag (default: latest)
  --registry <url>     Registry host (default: none; required for push)
  --prefix <path>      Namespace path inside the registry (default: none)
  --platform <arch>    Platform (default: linux/amd64)
  --no-latest          Don't tag/push as latest
  --no-cache           Build without Docker layer cache

Environment variables:
  IMAGE_NAME           Image name (default: fix-simulator)
  IMAGE_PREFIX         Namespace path (default: none)
  REGISTRY             Registry host (default: none)
  VERSION              Version tag

Examples:
  $0 build-push --version 1.2.0
  $0 build --no-cache
  $0 push --version 1.2.0 --registry registry.domain
  REGISTRY=registry.domain IMAGE_PREFIX=team $0 build-push --version 1.0.0
EOF
}

COMMAND="${1:-build-push}"
shift || true

# Parse options
NO_CACHE=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)   VERSION="$2"; shift 2;;
    --registry)  REGISTRY="$2"; shift 2;;
    --prefix)    IMAGE_PREFIX="$2"; shift 2;;
    --platform)  PLATFORM="$2"; shift 2;;
    --no-latest) PUSH_LATEST=false; shift;;
    --no-cache)  NO_CACHE=true; shift;;
    -h|--help|help) usage; exit 0;;
    *) log_error "Unknown option: $1"; usage; exit 1;;
  esac
done

REPO="${IMAGE_PREFIX:+$IMAGE_PREFIX/}$IMAGE_NAME"
LOCAL_IMAGE="$REPO:$VERSION"
REGISTRY_PATH="${REGISTRY:+$REGISTRY/}$REPO"
REGISTRY_IMAGE="$REGISTRY_PATH:$VERSION"
REGISTRY_LATEST="$REGISTRY_PATH:latest"

DEV_IMAGE="fix-simulator-dev:latest"   # toolbox: JDK 17, Maven, Node 20
M2_VOLUME="fix-simulator-m2"           # Maven and npm caches, kept between runs
CONTAINER_NAME="fix-simulator"

require_docker() {
    if ! command -v docker &>/dev/null; then
        log_error "docker not found in PATH. Install Docker, the only host requirement."
        exit 1
    fi
    if ! docker info &>/dev/null; then
        log_error "The Docker daemon is not running."
        exit 1
    fi
}

ensure_dev_image() {
    # Always run docker build: it reuses cached layers, and it picks up changes to Dockerfile.dev.
    docker build -q -t "$DEV_IMAGE" -f "$SCRIPT_DIR/Dockerfile.dev" "$SCRIPT_DIR" >/dev/null
}

# Run a command in the toolbox container as the current user, with the repository mounted at /src.
dev_run() {
    ensure_dev_image
    local tty=""
    if [ -t 0 ] && [ -t 1 ]; then tty="-it"; fi
    docker run --rm $tty \
        --user "$(id -u):$(id -g)" \
        -e HOME=/tmp/home \
        -e npm_config_cache=/m2/npm \
        -v "$SCRIPT_DIR:/src" \
        -v "$M2_VOLUME:/m2" \
        -w /src \
        "$DEV_IMAGE" "$@"
}

run_tests() {
    log_step "Running tests and coverage in the toolbox container"
    dev_run mvn -B -Dmaven.repo.local=/m2/repository verify ${MVN_ARGS:-}
    log_info "Coverage report: $SCRIPT_DIR/target/site/jacoco/index.html"
}

run_ui_build() {
    log_step "Building the admin UI in the toolbox container"
    dev_run sh -c 'cd admin-ui && npm ci --no-audit --no-fund && npm run build'
    log_info "UI written to $SCRIPT_DIR/admin-ui/dist"
}

run_container() {
    local token="${ADMIN_TOKEN:-}"
    if [ -z "$token" ]; then
        token="$(dev_run sh -c 'head -c 16 /dev/urandom | od -An -tx1 | tr -d " \n"' | tail -n 1)"
    fi

    log_step "Starting container $CONTAINER_NAME from $LOCAL_IMAGE"
    docker rm -f "$CONTAINER_NAME" &>/dev/null || true
    docker run -d --name "$CONTAINER_NAME" \
        -p 9876:9876 -p 8080:8080 \
        -e ADMIN_TOKEN="$token" \
        -v fix-store:/app/store \
        -v fix-logs:/app/logs \
        "$LOCAL_IMAGE" >/dev/null

    log_info "FIX port 9876, admin UI http://localhost:8080"
    log_info "Admin token: $token"
}

check_prerequisites() {
    log_step "Checking prerequisites..."

    if ! command -v docker &>/dev/null; then
        log_error "docker not found in PATH"
        exit 1
    fi

    if [ ! -f "$SCRIPT_DIR/Dockerfile" ]; then
        log_error "Dockerfile not found in $SCRIPT_DIR"
        exit 1
    fi

    if [ ! -f "$SCRIPT_DIR/pom.xml" ]; then
        log_error "pom.xml not found in $SCRIPT_DIR"
        exit 1
    fi

    log_info "Prerequisites OK"
}

build_image() {
    log_step "Building Docker image: $LOCAL_IMAGE"

    BUILD_ARGS="--platform $PLATFORM -t $LOCAL_IMAGE -f $SCRIPT_DIR/Dockerfile --build-arg SKIP_TESTS=${SKIP_TESTS:-false} $SCRIPT_DIR"

    if [ "$NO_CACHE" = true ]; then
        BUILD_ARGS="$BUILD_ARGS --no-cache"
    fi

    log_info "docker build $BUILD_ARGS"
    docker build $BUILD_ARGS

    log_info "Successfully built $LOCAL_IMAGE"
}

push_image() {
    log_step "Pushing image to registry: $REGISTRY"

    if [ -z "$REGISTRY" ]; then
        log_error "REGISTRY not set (use --registry <host> or REGISTRY=<host>)"
        exit 1
    fi

    # Tag for registry
    log_info "Tagging $LOCAL_IMAGE → $REGISTRY_IMAGE"
    docker tag "$LOCAL_IMAGE" "$REGISTRY_IMAGE"

    # Push versioned tag
    log_info "Pushing $REGISTRY_IMAGE"
    docker push "$REGISTRY_IMAGE"

    # Push latest tag if enabled
    if [ "$PUSH_LATEST" = true ]; then
        log_info "Tagging $LOCAL_IMAGE → $REGISTRY_LATEST"
        docker tag "$LOCAL_IMAGE" "$REGISTRY_LATEST"

        log_info "Pushing $REGISTRY_LATEST"
        docker push "$REGISTRY_LATEST"
    fi

    log_info "Successfully pushed to $REGISTRY"
}

clean_images() {
    log_step "Cleaning local images..."

    docker rmi "$LOCAL_IMAGE" 2>/dev/null       || log_warn "Image $LOCAL_IMAGE not found"
    docker rmi "$REGISTRY_IMAGE" 2>/dev/null    || log_warn "Image $REGISTRY_IMAGE not found"
    docker rmi "$REGISTRY_LATEST" 2>/dev/null   || log_warn "Image $REGISTRY_LATEST not found"

    log_info "Clean complete"
}

# Main
case "$COMMAND" in
  build)
    check_prerequisites
    require_docker
    build_image
    ;;
  run)
    require_docker
    run_container
    ;;
  test)
    require_docker
    run_tests
    ;;
  ui-build)
    require_docker
    run_ui_build
    ;;
  shell)
    require_docker
    dev_run bash
    ;;
  dev-image)
    require_docker
    ensure_dev_image
    ;;
  push)
    push_image
    ;;
  build-push)
    check_prerequisites
    build_image
    push_image
    ;;
  clean)
    clean_images
    ;;
  help)
    usage
    ;;
  *)
    log_error "Unknown command: $COMMAND"
    usage
    exit 1
    ;;
esac

log_info "Done!"
exit 0
