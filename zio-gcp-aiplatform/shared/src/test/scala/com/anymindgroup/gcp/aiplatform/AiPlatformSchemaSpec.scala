package com.anymindgroup.gcp.aiplatform

import zio.Chunk
import zio.schema.*
import zio.test.*

import com.anymindgroup.gcp.aiplatform.v1.schemas.GoogleCloudAiplatformV1Schema
import com.anymindgroup.jsoniter.Json.*

object AiPlatformSchemaSpec extends ZIOSpecDefault {
  import AiPlatformSchema.propsJsonCodec

  def spec = suite("AiPlatformSchemaSpec")(
    test("map ZIO Schema to CloudAiplatformV1Schema (primitives)") {
      case class TestCaseClassPrimitives(
        propStr: String,
        propStrOpt: Option[String],
        propInt: Int,
        propIntOpt: Option[Int],
        propLong: Long,
        propLongOpt: Option[Long],
      ) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestCaseClassPrimitives])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      for
        _ <- assertTrue(res.`type` == Some(GoogleCloudAiplatformV1Schema.Type.OBJECT))
        _ <- assertTrue(
               props == Map(
                 "propStr" -> GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                   nullable = Some(false),
                 ),
                 "propStrOpt" -> GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                   nullable = Some(true),
                 ),
                 "propInt" -> GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                   nullable = Some(false),
                 ),
                 "propIntOpt" -> GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                   nullable = Some(true),
                 ),
                 "propLong" -> GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                   nullable = Some(false),
                 ),
                 "propLongOpt" -> GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                   nullable = Some(true),
                 ),
               )
             )
      yield assertCompletes
    },
    test("marks every non-optional field required, and no optional one") {
      case class TestRequired(
        propStr: String,
        propStrOpt: Option[String],
        propInt: Int,
        propList: List[String],
        propListOpt: Option[List[String]],
      ) derives Schema

      val res = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestRequired])

      // Requiredness has to agree with the nullability emitted for the same
      // field: a field is required exactly when its own schema is not nullable.
      val props    = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)
      val nullable = props.collect { case (name, s) if s.nullable.contains(true) => name }.toSet

      assertTrue(
        res.required.map(_.toSet) == Some(Set("propStr", "propInt", "propList")),
        res.required.map(_.toSet).exists(r => r.intersect(nullable).isEmpty),
        nullable == Set("propStrOpt", "propListOpt"),
      )
    },
    test("marks a nested object's own fields required") {
      // The nested object is where it counts. An outer `required` naming the
      // list does not oblige the model to fill in anything *inside* the list's
      // elements.
      case class TestNestedItem(itemStr: String, itemInt: Int, itemStrOpt: Option[String]) derives Schema
      case class TestNestedOuter(items: List[TestNestedItem], outerOpt: Option[String]) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestNestedOuter])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)
      val item  = props.get("items").flatMap(_.items)

      assertTrue(
        res.required.map(_.toSet) == Some(Set("items")),
        item.flatMap(_.required.map(_.toSet)) == Some(Set("itemStr", "itemInt")),
      )
    },
    test("omits required entirely when every field is optional") {
      case class AllOptional(a: Option[String], b: Option[Int]) derives Schema

      assertTrue(AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[AllOptional]).required.isEmpty)
    },
    test("orders properties by declaration, not by the properties map") {
      // `properties` is a Map, so its iteration order is hash-derived and says
      // nothing about the case class. More than four fields, to be past the
      // point where Scala's small-map implementations happen to preserve
      // insertion order.
      case class Ordered(zebra: String, apple: String, mango: String, kiwi: String, banana: String) derives Schema

      val res = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[Ordered])

      assertTrue(
        res.propertyOrdering == Some(Chunk("zebra", "apple", "mango", "kiwi", "banana"))
      )
    },
    test("map ZIO Schema to CloudAiplatformV1Schema (array)") {
      case class TestCaseClassArrays(
        arrayStr: List[String],
        arrayStrOpt: Option[List[String]],
        arrayInt: List[Int],
      ) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestCaseClassArrays])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      assertTrue(
        props == Map(
          "arrayStr" -> GoogleCloudAiplatformV1Schema(
            `type` = Some(GoogleCloudAiplatformV1Schema.Type.ARRAY),
            nullable = Some(false),
            items = Some(
              GoogleCloudAiplatformV1Schema(
                `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                nullable = Some(false),
              )
            ),
          ),
          "arrayStrOpt" -> GoogleCloudAiplatformV1Schema(
            `type` = Some(GoogleCloudAiplatformV1Schema.Type.ARRAY),
            nullable = Some(true),
            items = Some(
              GoogleCloudAiplatformV1Schema(
                `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                nullable = Some(false),
              )
            ),
          ),
          "arrayInt" -> GoogleCloudAiplatformV1Schema(
            `type` = Some(GoogleCloudAiplatformV1Schema.Type.ARRAY),
            nullable = Some(false),
            items = Some(
              GoogleCloudAiplatformV1Schema(
                `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                nullable = Some(false),
              )
            ),
          ),
        )
      )
    },
    test("map ZIO Schema to CloudAiplatformV1Schema (anyOf)") {
      case class TestCaseClassEither(eitherValue: Either[String, Int]) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestCaseClassEither])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      assertTrue(
        props == Map(
          "eitherValue" -> GoogleCloudAiplatformV1Schema(
            anyOf = Some(
              Chunk(
                GoogleCloudAiplatformV1Schema(
                  `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                  nullable = Some(false),
                ),
                GoogleCloudAiplatformV1Schema(
                  `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                  nullable = Some(false),
                ),
              )
            )
          )
        )
      )
    },
    test("map ZIO Schema to CloudAiplatformV1Schema (enum)") {
      enum TestEnum:
        case Val1, Val2, Val3
      case class TestCaseClassEnum(enumValue: TestEnum) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestCaseClassEnum])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      assertTrue(
        props == Map(
          "enumValue" -> GoogleCloudAiplatformV1Schema(
            `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
            `enum` = Some(Chunk("Val1", "Val2", "Val3")),
          )
        )
      )
    },
    test("map ZIO Schema to CloudAiplatformV1Schema (enum with more than 3 cases)") {
      enum TestBigEnum:
        case Val1, Val2, Val3, Val4, Val5
      case class TestCaseClassBigEnum(enumValue: TestBigEnum) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestCaseClassBigEnum])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      assertTrue(
        props == Map(
          "enumValue" -> GoogleCloudAiplatformV1Schema(
            `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
            `enum` = Some(Chunk("Val1", "Val2", "Val3", "Val4", "Val5")),
          )
        )
      )
    },
    test("map ZIO Schema to CloudAiplatformV1Schema (map)") {
      case class TestCaseClassMap(
        mapValue: Map[String, Int],
        mapValueOpt: Option[Map[String, String]],
      ) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestCaseClassMap])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      val mapValue    = props.get("mapValue")
      val mapValueOpt = props.get("mapValueOpt")

      for
        _ <- assertTrue(mapValue.flatMap(_.`type`) == Some(GoogleCloudAiplatformV1Schema.Type.OBJECT))
        _ <- assertTrue(mapValue.flatMap(_.nullable) == Some(false))
        _ <- assertTrue(
               mapValue.flatMap(_.additionalProperties).map(_.readAsUnsafe[GoogleCloudAiplatformV1Schema]) == Some(
                 GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                   nullable = Some(false),
                 )
               )
             )
        _ <- assertTrue(mapValueOpt.flatMap(_.`type`) == Some(GoogleCloudAiplatformV1Schema.Type.OBJECT))
        _ <- assertTrue(mapValueOpt.flatMap(_.nullable) == Some(true))
        _ <- assertTrue(
               mapValueOpt.flatMap(_.additionalProperties).map(_.readAsUnsafe[GoogleCloudAiplatformV1Schema]) == Some(
                 GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                   nullable = Some(false),
                 )
               )
             )
      yield assertCompletes
    },
    test("map ZIO Schema to CloudAiplatformV1Schema (nested case class)") {
      case class Inner(innerStr: String) derives Schema
      case class Outer(name: String, inner: Inner) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[Outer])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      val innerSchema = props.get("inner")
      val innerProps  =
        innerSchema
          .flatMap(_.properties)
          .map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]])
          .getOrElse(Map.empty)

      for
        _ <- assertTrue(res.`type` == Some(GoogleCloudAiplatformV1Schema.Type.OBJECT))
        _ <- assertTrue(
               props.get("name") == Some(
                 GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                   nullable = Some(false),
                 )
               )
             )
        _ <- assertTrue(innerSchema.flatMap(_.`type`) == Some(GoogleCloudAiplatformV1Schema.Type.OBJECT))
        _ <- assertTrue(
               innerProps == Map(
                 "innerStr" -> GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                   nullable = Some(false),
                 )
               )
             )
      yield assertCompletes
    },
    test("map ZIO Schema to CloudAiplatformV1Schema (case class with more than 16 fields)") {
      case class TestCaseClass17(
        f1: String,
        f2: String,
        f3: String,
        f4: String,
        f5: String,
        f6: String,
        f7: String,
        f8: String,
        f9: String,
        f10: String,
        f11: String,
        f12: String,
        f13: String,
        f14: String,
        f15: String,
        f16: String,
        f17: Int,
      ) derives Schema

      val res   = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(Schema[TestCaseClass17])
      val props = res.properties.map(_.readAsUnsafe[Map[String, GoogleCloudAiplatformV1Schema]]).getOrElse(Map.empty)

      for
        _ <- assertTrue(res.`type` == Some(GoogleCloudAiplatformV1Schema.Type.OBJECT))
        _ <- assertTrue(props.size == 17)
        _ <- assertTrue(
               props.get("f1") == Some(
                 GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.STRING),
                   nullable = Some(false),
                 )
               )
             )
        _ <- assertTrue(
               props.get("f17") == Some(
                 GoogleCloudAiplatformV1Schema(
                   `type` = Some(GoogleCloudAiplatformV1Schema.Type.NUMBER),
                   nullable = Some(false),
                 )
               )
             )
      yield assertCompletes
    },
  )
}
