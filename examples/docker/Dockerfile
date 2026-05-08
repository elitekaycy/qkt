FROM ghcr.io/elitekaycy/qkt:0.13.0
COPY strategies/ /strategies/
COPY qkt.config.yaml /etc/qkt/qkt.config.yaml
ENV QKT_CONFIG=/etc/qkt/qkt.config.yaml
# CMD inherited: qkt daemon --load-dir /strategies
