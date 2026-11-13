# A throwaway SSH server with both the herdr TUI and herdr-core, for the HerdrDroid backend tests.
FROM ubuntu:24.04
RUN apt-get update -qq && apt-get install -y -qq openssh-server curl ca-certificates python3 procps git bash >/dev/null && rm -rf /var/lib/apt/lists/* && mkdir -p /run/sshd
RUN useradd -m -s /bin/bash dev
USER dev
RUN curl -fsSL https://releases.herdr.dev/core/install.sh | sh && curl -fsSL https://herdr.dev/install.sh | sh
USER root
RUN echo 'export PATH=$HOME/.local/bin:$PATH' >> /home/dev/.profile && echo 'export PATH=$HOME/.local/bin:$PATH' >> /home/dev/.bashrc
COPY entry.sh /entry.sh
COPY fake-claude /usr/local/bin/claude
CMD ["bash", "/entry.sh"]
