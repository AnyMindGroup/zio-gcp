package com.anymindgroup.pubsub

import zio.Chunk

type Receipt[E] = (ReceivedMessage[E], AckReply)
type RawReceipt = Receipt[Chunk[Byte]]
