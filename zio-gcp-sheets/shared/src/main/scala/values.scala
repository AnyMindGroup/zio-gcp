package com.anymindgroup.gcp.sheets

import com.anymindgroup.gcp.sheets.v4.schemas.ValueRange
import com.anymindgroup.gcp.sheets.v4.schemas.ValueRange.MajorDimension
import com.anymindgroup.jsoniter.Json
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import zio.*

// For input, supported value types are: bool, string, and double
type ValueRangeCell = String | Boolean | Double
object ValueRangeCell:
  given codec: JsonValueCodec[ValueRangeCell] = new JsonValueCodec {
    val str  = JsonCodecMaker.make[String]
    val db   = JsonCodecMaker.make[Double]
    val bool = JsonCodecMaker.make[Boolean]

    override def decodeValue(in: JsonReader, default: ValueRangeCell): ValueRangeCell =
      in.nextToken() match
        case '"' =>
          in.rollbackToken()
          in.readString("")
        case 't' | 'f' =>
          in.rollbackToken()
          in.readBoolean()
        case _ =>
          in.rollbackToken()
          in.readDouble()

    override def encodeValue(x: ValueRangeCell, out: JsonWriter): Unit =
      x match
        case v: Double  => db.encodeValue(v, out)
        case v: Boolean => bool.encodeValue(v, out)
        case v: String  => str.encodeValue(v, out)

    override def nullValue: ValueRangeCell = ""
  }

def toWriteValueRange(
  rows: Chunk[Chunk[ValueRangeCell]]
): ValueRange = ValueRange(
  values = Option(rows.map(_.map(c => Json.writeToJson(c)(using ValueRangeCell.codec)))),
  majorDimension = Some(MajorDimension.ROWS),
  // not needed for writing
  range = None,
)

def readValuesFromRange(
  range: ValueRange
): Either[Throwable, Chunk[Chunk[ValueRangeCell]]] = range.values match
  case Some(value) =>
    try Right(value.map(_.map(_.readAsUnsafe[ValueRangeCell](using ValueRangeCell.codec))))
    catch case e: Throwable => Left(e)
  case None => Right(Chunk.empty)
