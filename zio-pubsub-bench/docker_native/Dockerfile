FROM alpine:latest

RUN apk update && apk add gcompat libuv curl
ADD --chmod=+x bin/zio_pubsub_test /usr/local/bin/zio_pubsub_test

ENTRYPOINT ["/usr/local/bin/zio_pubsub_test"]
CMD []