# Test server for BackendE2ETest

A throwaway SSH server with both the herdr TUI and herdr-core (the Herdr App's server), plus a
stand-in `claude` that echoes what it is sent. Build and run it on a test machine, then point the
build at it:

    docker build -t hc-e2e tools/hc-e2e
    docker network create hc-e2e-net
    mkdir -p keys && docker run -d --name hc-e2e-srv --network hc-e2e-net -v "$PWD/keys:/keys" hc-e2e
    HD_DOCKER_ARGS="--network hc-e2e-net -v $PWD/keys:/hckeys:ro -e HD_HC_HOST=hc-e2e-srv -e HD_HC_KEY=/hckeys/id -e HD_HC_USER=dev" \
      bash build-in-docker.sh <work folder>

The container makes its own throwaway key pair in `keys/` on first start. Remove the container,
network and image afterwards.
