#!/usr/bin/env bash
# fw-nemo.cn 的 Certbot deploy hook；初次签发后也可手动执行。
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo 'Run with sudo.' >&2
    exit 1
fi

domain=fw-nemo.cn
expected_lineage="/etc/letsencrypt/live/$domain"
lineage="${RENEWED_LINEAGE:-$expected_lineage}"
# 其他站点续期时不要覆盖 NemoOJ 的证书。
if [[ "$lineage" != "$expected_lineage" ]]; then
    exit 0
fi

openssl verify -verify_hostname "$domain" -untrusted "$lineage/chain.pem" "$lineage/cert.pem" >/dev/null
openssl x509 -in "$lineage/fullchain.pem" -noout -checkend 0 >/dev/null
cert_key=$(openssl x509 -in "$lineage/fullchain.pem" -pubkey -noout |
    openssl pkey -pubin -outform DER | sha256sum)
private_key=$(openssl pkey -in "$lineage/privkey.pem" -pubout -outform DER | sha256sum)
if [[ "$cert_key" != "$private_key" ]]; then
    echo 'Certificate and private key do not match.' >&2
    exit 1
fi

destination=/srv/nemo-oj/deploy/nginx/certs
install -d -m 700 "$destination"
install -m 644 "$lineage/fullchain.pem" "$destination/fullchain.pem"
install -m 600 "$lineage/privkey.pem" "$destination/privkey.pem"

# 首次签发时公网代理尚未启动；只复制证书。续期时验证后热加载。
if [[ "$(docker container inspect --format '{{.State.Running}}' nemo-oj-proxy-1 2>/dev/null || true)" == true ]]; then
    docker exec nemo-oj-proxy-1 nginx -t
    docker exec nemo-oj-proxy-1 nginx -s reload
fi
