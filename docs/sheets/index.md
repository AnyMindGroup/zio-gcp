# Sheets

Client for [Google Sheets API](https://developers.google.com/workspace/sheets/api/reference/rest).

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-sheets-v4" % "@VERSION@",
)
```

## Usage examples

<<< @/../examples/shared/src/main/scala/sheets_example.scala{scala}
