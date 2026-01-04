package com.anymindgroup.gcp.sheets

import zio.test.*
import zio.test.Assertion.{hasSameElements, isRight}
import zio.{Chunk, Scope}

object SheetsSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SheetsSpec")(
    test("encode/decode single row") {
      val values        = Chunk(Chunk("single"))
      val range         = toWriteValueRange(values)
      val readValuesRes = readValuesFromRange(range)

      assert(readValuesRes)(isRight(hasSameElements(values)))
    },
    test("encode/decode special characters") {
      val values        = Chunk(Chunk("hello\nworld", "tab\there", "quote\"test", "unicode™®©"))
      val range         = toWriteValueRange(values)
      val readValuesRes = readValuesFromRange(range)

      assert(readValuesRes)(isRight(hasSameElements(values)))
    },
    test("encode/decode numeric edge cases") {
      val values        = Chunk(Chunk(0d, -1d, -123.456d, Double.MinPositiveValue, Double.MaxValue))
      val range         = toWriteValueRange(values)
      val readValuesRes = readValuesFromRange(range)

      assert(readValuesRes)(isRight(hasSameElements(values)))
    },
    test("encode/decode boolean values") {
      val values        = Chunk(Chunk(true, false, true, false))
      val range         = toWriteValueRange(values)
      val readValuesRes = readValuesFromRange(range)

      assert(readValuesRes)(isRight(hasSameElements(values)))
    },
    test("encode/decode mixed types with empty strings") {
      val values        = Chunk(Chunk("", 42d, "abc", "", true, "efg"))
      val range         = toWriteValueRange(values)
      val readValuesRes = readValuesFromRange(range)

      assert(readValuesRes)(isRight(hasSameElements(values)))
    },
  )
}
