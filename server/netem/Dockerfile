# Tiny helper image with `tc`, used to add latency to the nginx container's network
# namespace (docker run --network container:hcb-nginx --cap-add NET_ADMIN). No sudo needed.
FROM alpine:3.24.3
RUN apk add --no-cache iproute2-tc
ENTRYPOINT ["tc"]
