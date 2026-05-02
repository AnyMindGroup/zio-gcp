# Load tests to run in the Google Cloud with actual Pub/Sub

Running with emulator command examples:
```bash
# google impl
PUBSUB_EMULATOR=localhost:8085 sbt 'zioPubsubBenchGoogle/run -- -label=test -messages-amount=1000 -batch-size=100'

# http on jvm
PUBSUB_EMULATOR=localhost:8085 sbt 'zioPubsubBenchHttpJVM/run -- -label=test -messages-amount=1000 -batch-size=100'

# native build
SCALANATIVE_MODE=release-fast sbt zioPubsubBenchHttpNative/nativeLink
# native run
PUBSUB_EMULATOR=localhost:8085 ./zio-pubsub-bench/test-http/native/target/scala-3.8.3/ziopubsubbenchhttp -label=test -messages-amount=1000 -batch-size=100
```