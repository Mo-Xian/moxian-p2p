# moxian-p2p client Docker 镜像
# 多阶段：Go 静态编译 + 极小运行时
# 用法见 examples/nas-stack/docker-compose.yml
#
# 镜像入口为 moxian-client 默认读 /etc/moxian/client.yaml
# 覆盖：docker run -v ./my.yaml:/etc/moxian/client.yaml ghcr.io/mo-xian/moxian-p2p:latest

FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS builder

ARG TARGETOS
ARG TARGETARCH

WORKDIR /src
COPY . .

# CGO 关闭 完全静态 链接 方便在 scratch / alpine 上跑
# 不同架构用 buildx 自动传 TARGETOS/TARGETARCH
# 不单独 go mod download：项目 go.sum 个别子包入口不全 go build 会按需解析更宽松
RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/go/pkg/mod \
    CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags="-s -w" -o /out/moxian-client ./cmd/client

# ---- 运行时 ----
FROM alpine:3.20

# TUN 设备 + 时区 + 证书 运行时必备
RUN apk add --no-cache ca-certificates tzdata iptables iproute2 \
    && mkdir -p /etc/moxian

COPY --from=builder /out/moxian-client /usr/local/bin/moxian-client

# 默认读 /etc/moxian/client.yaml 挂载时直接覆盖这个文件即可
ENTRYPOINT ["/usr/local/bin/moxian-client"]
CMD ["-config", "/etc/moxian/client.yaml"]
