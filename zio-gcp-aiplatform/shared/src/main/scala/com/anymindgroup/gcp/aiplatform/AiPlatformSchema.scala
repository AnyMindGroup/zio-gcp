package com.anymindgroup.gcp.aiplatform

import zio.Chunk
import zio.schema.Schema.{Case, Field}
import zio.schema.{Schema, StandardType}

import com.anymindgroup.gcp.aiplatform.v1.schemas.GoogleCloudAiplatformV1Schema
import com.anymindgroup.jsoniter.Json
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

object AiPlatformSchema {
  given propsJsonCodec: JsonValueCodec[Map[String, GoogleCloudAiplatformV1Schema]] = JsonCodecMaker.make

  def toGoogleCloudAiplatformV1Schema[A](schema: Schema[A]): GoogleCloudAiplatformV1Schema = schema match
    case Schema.Sequence(elementSchema, _, _, _, _) =>
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.ARRAY),
        items = Some(toGoogleCloudAiplatformV1Schema(elementSchema)),
        nullable = Some(false),
      )

    case Schema.NonEmptySequence(elementSchema, _, _, _, _) =>
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.ARRAY),
        items = Some(toGoogleCloudAiplatformV1Schema(elementSchema)),
        nullable = Some(false),
      )

    case Schema.Set(elementSchema, _) =>
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.ARRAY),
        items = Some(toGoogleCloudAiplatformV1Schema(elementSchema)),
        nullable = Some(false),
      )

    case Schema.Primitive(standardType, _) => primitiveSchema(standardType)

    case Schema.Optional(schema, _) =>
      toGoogleCloudAiplatformV1Schema(schema).copy(nullable = Some(true))

    case Schema.Tuple2(left, right, _) =>
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.OBJECT),
        properties = Some(
          Json.writeToJson(
            Map(
              "_1" -> toGoogleCloudAiplatformV1Schema(left),
              "_2" -> toGoogleCloudAiplatformV1Schema(right),
            )
          )
        ),
      )
    case Schema.Either(left, right, _) =>
      GoogleCloudAiplatformV1Schema(
        anyOf = Some(Chunk(toGoogleCloudAiplatformV1Schema(left), toGoogleCloudAiplatformV1Schema(right)))
      )
    case Schema.Fallback(left, right, _, _) =>
      GoogleCloudAiplatformV1Schema(
        anyOf = Some(Chunk(toGoogleCloudAiplatformV1Schema(left), toGoogleCloudAiplatformV1Schema(right)))
      )
    case Schema.Lazy(schema0) => toGoogleCloudAiplatformV1Schema(schema0())

    case record: Schema.Record[?] => fromFields(record.fields)

    case enumSchema: Schema.Enum[?] => fromEnum(enumSchema.cases)

    case Schema.Map(_, valueSchema, _) =>
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.OBJECT),
        additionalProperties = Some(Json.writeToJson(toGoogleCloudAiplatformV1Schema(valueSchema))),
        nullable = Some(false),
      )
    case Schema.NonEmptyMap(_, valueSchema, _) =>
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.OBJECT),
        additionalProperties = Some(Json.writeToJson(toGoogleCloudAiplatformV1Schema(valueSchema))),
        nullable = Some(false),
      )

    case Schema.Transform(schema0, _, _, _, _) => toGoogleCloudAiplatformV1Schema(schema0)

    case Schema.Dynamic(_) | Schema.Fail(_, _) =>
      GoogleCloudAiplatformV1Schema(`type` = Some(GoogleCloudAiplatformV1Schema.Type.TYPE_UNSPECIFIED))

    case _ =>
      GoogleCloudAiplatformV1Schema(`type` = Some(GoogleCloudAiplatformV1Schema.Type.TYPE_UNSPECIFIED))

  private def fromFields(fields: Seq[Field[?, ?]]): GoogleCloudAiplatformV1Schema =
    GoogleCloudAiplatformV1Schema(
      `type` = Some(GoogleCloudAiplatformV1Schema.Type.OBJECT),
      properties = Some(
        Json.writeToJson(
          fields.map { f =>
            f.fieldName -> toGoogleCloudAiplatformV1Schema(f.schema)
          }.toMap
        )
      ),
    )

  private def fromEnum(cases: Seq[Case[?, ?]]): GoogleCloudAiplatformV1Schema =
    val types = cases.map {
      _.schema match
        case Schema.CaseClass0(_, _, _)                   => GoogleCloudAiplatformV1Schema.Type.STRING
        case Schema.Primitive(StandardType.StringType, _) => GoogleCloudAiplatformV1Schema.Type.STRING
        case Schema.Primitive(StandardType.IntType, _)    => GoogleCloudAiplatformV1Schema.Type.NUMBER
        case Schema.Primitive(StandardType.ShortType, _)  => GoogleCloudAiplatformV1Schema.Type.NUMBER
        case Schema.Primitive(StandardType.LongType, _)   => GoogleCloudAiplatformV1Schema.Type.NUMBER
        case _                                            => GoogleCloudAiplatformV1Schema.Type.TYPE_UNSPECIFIED
    }.toSet

    if types == Set(GoogleCloudAiplatformV1Schema.Type.STRING) then
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
        `enum` = Some(cases.toSeq.map(c => c.caseName).to(Chunk)),
      )
    else if types == Set(GoogleCloudAiplatformV1Schema.Type.NUMBER) then
      GoogleCloudAiplatformV1Schema(
        `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
        `enum` = Some(cases.toSeq.map(c => c.caseName).to(Chunk)),
      )
    else GoogleCloudAiplatformV1Schema(`type` = Some(GoogleCloudAiplatformV1Schema.Type.TYPE_UNSPECIFIED))

  private def primitiveSchema[A](standardType: StandardType[A]): GoogleCloudAiplatformV1Schema = {
    import zio.schema.StandardType.*

    def toSchema(typ: GoogleCloudAiplatformV1Schema.Type) =
      GoogleCloudAiplatformV1Schema(
        `type` = Some(typ),
        nullable = Some(false),
      )

    standardType match
      case UnitType           => toSchema(GoogleCloudAiplatformV1Schema.Type.NULL)
      case StringType         => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case BoolType           => toSchema(GoogleCloudAiplatformV1Schema.Type.BOOLEAN)
      case ByteType           => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case ShortType          => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case IntType            => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case LongType           => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case FloatType          => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case DoubleType         => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case BinaryType         => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case CharType           => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case UUIDType           => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case CurrencyType       => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case BigDecimalType     => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case BigIntegerType     => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case DayOfWeekType      => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case MonthType          => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case MonthDayType       => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case PeriodType         => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case YearType           => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case YearMonthType      => toSchema(GoogleCloudAiplatformV1Schema.Type.NUMBER)
      case ZoneIdType         => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case ZoneOffsetType     => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case DurationType       => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case InstantType        => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case LocalDateType      => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case LocalTimeType      => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case LocalDateTimeType  => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case OffsetTimeType     => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case OffsetDateTimeType => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
      case ZonedDateTimeType  => toSchema(GoogleCloudAiplatformV1Schema.Type.STRING)
  }
}
