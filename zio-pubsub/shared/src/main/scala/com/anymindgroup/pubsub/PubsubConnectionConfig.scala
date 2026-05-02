package com.anymindgroup.pubsub

enum PubsubConnectionConfig:
  case Cloud
  case Emulator(host: String, port: Int = 8085)
