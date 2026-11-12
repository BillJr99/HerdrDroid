# A throwaway SSH-over-WebSocket server for HerdrDroid's end-to-end test (WsBridgeTest).
# sshd accepts keys only and has an ed25519 host key; websockify carries its bytes as binary
# WebSocket frames on port 8080; a stand-in herdr lists four agents. Keys are made at build time
# for this container only. Run (from the work folder, with src/ and e2e/):
#   sudo docker build -t hdws-test src/tools/ws-test-server && sudo docker network create hdws
#   sudo docker run -d --name hdws-srv --network hdws hdws-test
#   sudo docker cp hdws-srv:/tmp/good e2e/good; sudo docker cp hdws-srv:/tmp/bad e2e/bad
#   sudo docker exec hdws-srv ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | cut -d' ' -f2 > e2e/fp
#   HD_DOCKER_ARGS="--network hdws -e HD_WS_URL=ws://hdws-srv:8080/ -e HD_WS_KEY=/w/e2e/good \
#     -e HD_WS_BADKEY=/w/e2e/bad -e HD_WS_FP=$(cat e2e/fp)" bash src/build-in-docker.sh .
# usermod -p '*' unlocks the account without a usable password: with UsePAM no, sshd refuses a
# locked ("!") account even with a valid key.
FROM debian:bookworm-slim
RUN apt-get update -qq && apt-get install -y -qq openssh-server websockify python3 >/dev/null && rm -rf /var/lib/apt/lists/*
RUN useradd -m -s /bin/bash node && usermod -p "*" node && mkdir -p /home/node/.ssh /home/node/.local/bin /run/sshd \
 && rm -f /etc/ssh/ssh_host_* && ssh-keygen -q -t ed25519 -N "" -f /etc/ssh/ssh_host_ed25519_key \
 && ssh-keygen -q -t ecdsa -b 256 -N "" -m PEM -f /tmp/good -C good && ssh-keygen -q -t ed25519 -N "" -f /tmp/bad -C bad \
 && cp /tmp/good.pub /home/node/.ssh/authorized_keys && chown -R node:node /home/node && chmod 700 /home/node/.ssh && chmod 600 /home/node/.ssh/authorized_keys
RUN printf "HostKey /etc/ssh/ssh_host_ed25519_key\nPasswordAuthentication no\nKbdInteractiveAuthentication no\nPubkeyAuthentication yes\nUsePAM no\nAllowUsers node\n" > /etc/ssh/sshd_config.d/test.conf
RUN printf "%s\n" "#!/bin/sh" "case \"\$1 \$2\" in" "\"agent list\") echo '{\"result\":{\"agents\":[{\"name\":\"claude\",\"status\":\"idle\"},{\"name\":\"codex\",\"status\":\"working\"},{\"name\":\"grok\",\"status\":\"idle\"},{\"name\":\"agy\",\"status\":\"blocked\"}]}}' ;;" "\"pane read\") echo \"pane output line\" ;;" "*) echo herdr 0.9.5 ;;" "esac" > /home/node/.local/bin/herdr && chmod 755 /home/node/.local/bin/herdr
CMD /usr/sbin/sshd && exec websockify 0.0.0.0:8080 127.0.0.1:22
