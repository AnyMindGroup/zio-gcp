# Authentication

The module `zio-gcp-auth` provides methods for authentication.  
It's primarily meant to run on a VM in Google Cloud and make use of [compute metadata](https://cloud.google.com/compute/docs/metadata/overview).

Currently supported credentials and tokens:

| Credentials | [Access token](https://cloud.google.com/docs/authentication/token-types#access) | [ID token](https://cloud.google.com/docs/authentication/token-types#id) |
| --- | --- | --- |
| [Service account](https://cloud.google.com/docs/authentication#service-accounts) (via [compute metadata](https://cloud.google.com/compute/docs/metadata/overview)) | ✅ | ✅ |
| [User credentials](https://cloud.google.com/docs/authentication/application-default-credentials#personal) | ✅ | ❌ |
| [Impersonated service account](https://cloud.google.com/iam/docs/impersonating-service-accounts) | ✅ | ❌ |
| Service account (via private key) | ❌ | ❌ |

## Overriding the metadata server address

By default the compute metadata server is looked up at `metadata.google.internal`, which resolves
on a Google Cloud VM and nowhere else. Set `GCE_METADATA_HOST` to send those requests somewhere
else — a [metadata server emulator](https://github.com/salrashid123/gce_metadata_server) in a dev
container, a test double, or the `169.254.169.254` address when DNS is unavailable:

```sh
export GCE_METADATA_HOST=127.0.0.1:8080
```

The value is an authority — `host` or `host:port` — and takes no scheme. This is the same variable
Google's own client libraries read, so an environment already set up for one of those needs no
further configuration here.

With it set, `TokenProvider.defaultAccessTokenProvider` finds compute credentials off Google Cloud
exactly as it would on a VM, which means local runs need neither a service account key nor
`gcloud auth application-default login`.

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@"
)
```

## Token provider usage examples

<<< @/../examples/shared/src/main/scala/token_provider_examples.scala{scala}

