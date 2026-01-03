package com.anymindgroup.jsoniter

import com.github.plokhotnyuk.jsoniter_scala.core.*

/* type that represent a raw json value */
opaque type Json = Array[Byte]
object Json:
  def writeToJson[T: JsonValueCodec](v: T): Json = writeToArray[T](v)

  given codec: JsonValueCodec[Json] = new JsonValueCodec[Json]:
    override def decodeValue(in: JsonReader, default: Json): Json = in.readRawValAsBytes()
    override def encodeValue(x: Json, out: JsonWriter): Unit      = out.writeRawVal(x)
    override val nullValue: Json                                  = new Array[Byte](0)

  extension (v: Json)
    def readAsUnsafe[T: JsonValueCodec]: T              = readFromArray(v)
    def readAs[T: JsonValueCodec]: Either[Throwable, T] =
      try Right(readFromArray(v))
      catch case t: Throwable => Left(t)
