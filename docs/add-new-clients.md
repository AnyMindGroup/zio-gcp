---
outline: deep
---

# Usage Examples

## Client usage examples


## Adding new clients
Look up and place the [discovery document](https://developers.google.com/discovery/v1/using) specs into the `codegen/src/main/resources` folder.  
E.g. like:
```shell
curl 'https://redis.googleapis.com/$discovery/rest?version=v1' > codegen/src/main/resources/redis_v1.json
```

In `build.sbt` find and extend the config for clients code to generate:
```scala
lazy val gcpClientsCrossProjects: Seq[CrossProject] =
  Seq(
    "aiplatform"     -> "v1",
    "iamcredentials" -> "v1",
    "pubsub"         -> "v1",
    "storage"        -> "v1",
    // new clients can be added here
    // 1. Place the specs into codegen/src/main/resources folder e.g.:
    // curl 'https://redis.googleapis.com/$discovery/rest?version=v1' > codegen/src/main/resources/redis_v1.json
    // 2. add to configuration here according to the json file name "redis_v1.json" like:
    // "redis"          -> "v1",
  ).flatMap { case (apiName, apiVersion) =>
    // ... 
  }                     
```
_This step could be automated in the future._

Done. The package will be available as `"com.anymindgroup::zio-gcp-redis-v1"` on publishing.
