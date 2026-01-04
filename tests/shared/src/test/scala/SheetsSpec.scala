package com.anymindgroup.gcp.sheets

import zio.test.*
import zio.test.Assertion.{hasSameElements, isRight}
import zio.{Chunk, Scope}

object SheetsSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SheetsSpec")(
    test("encode/decode values") {
      val values = Chunk(Chunk("my string val 1", 1d, true, 2.12d, false, "my string val 2"))
      // encoded
      val range = toWriteValueRange(values)
      // decoded
      val readValuesRes = readValuesFromRange(range)

      assert(readValuesRes)(isRight(hasSameElements(values)))
    }
  )
}
