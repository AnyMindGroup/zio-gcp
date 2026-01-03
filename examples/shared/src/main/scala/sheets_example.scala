//> using scala 3.7.4
//> using dep com.anymindgroup::zio-gcp-sheets-v4::0.2.5

import zio.*, zio.ZIO.{logInfo, logError, dieMessage}
import com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.sheets.v4.resources.Spreadsheets
import com.anymindgroup.gcp.sheets.v4.resources.spreadsheets.Values
import com.anymindgroup.gcp.sheets.v4.schemas.{Spreadsheet, SpreadsheetProperties, ValueRange}
import sttp.model.Header, sttp.client4.Response
import com.anymindgroup.jsoniter.Json
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.core.*
import scala.util.{Try, Failure, Success}

// add https://www.googleapis.com/auth/spreadsheets scope to application default credentials
// gcloud auth application-default login --scopes=openid,https://www.googleapis.com/auth/userinfo.email,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/sqlservice.login,https://www.googleapis.com/auth/spreadsheets
object sheets_example extends ZIOAppDefault:
  def run =
    for
      backend <- defaultAccessTokenBackend()
      // for use with user credentials a quota project is required:
      // https://docs.cloud.google.com/docs/authentication/troubleshoot-adc#user-creds-client-based
      // https://docs.cloud.google.com/docs/authentication/rest#set-billing-project
      xGoogUserProject = Header("x-goog-user-project", "my-gcp-project")

      // Create a spreadsheet with a title
      createReq  = Spreadsheet(properties = Some(SpreadsheetProperties(title = Some("zio-gcp example spreadsheet"))))
      (id, url) <- backend
                     .send(
                       Spreadsheets
                         .create(
                           request = createReq
                         )
                         .header(xGoogUserProject)
                     )
                     .flatMap:
                       case Response(body = Right(sheet)) =>
                         sheet.spreadsheetId match
                           case Some(sheetId) => ZIO.succeed((sheetId, sheet.spreadsheetUrl.getOrElse("(unknown)")))
                           case None          => dieMessage("Missing spreadsheetId")
                       case Response(body = Left(err)) => dieMessage(s"Failure on creating spreadsheet: ${err}")
      _     <- logInfo(s"Created spreadsheet: $url (id=$id)")
      values = ValueRange(
                 values = toValues(
                   SheetValueRow("Name", "Age", "City", "Over 18"),
                   SheetValueRow("Alice", 30, "New York", true),
                   SheetValueRow("Bob", 17, "San Francisco", false),
                 )
               )
      _ <- backend
             .send(
               Values
                 .update(
                   spreadsheetId = id,
                   range = "Sheet1!A1:D3",
                   request = values,
                   valueInputOption = Some("RAW"),
                 )
                 .header(xGoogUserProject)
             )
             .flatMap:
               case Response(body = Right(body)) => logInfo(s"Wrote values to spreadsheet: ${body}")
               case Response(body = Left(err))   => logError(s"Failed to write values: $err")
    yield ()

  private def toValues(rows: SheetValueRow*) = Option(Chunk(rows*).map(_.toJson))

type SheetValueCell       = String | Boolean | Double
opaque type SheetValueRow = Chunk[SheetValueCell]
object SheetValueRow:
  def apply(values: SheetValueCell*): SheetValueRow = Chunk(values*)

  given cellCodec: JsonValueCodec[SheetValueCell] = new JsonValueCodec {
    val str  = JsonCodecMaker.make[String]
    val db   = JsonCodecMaker.make[Double]
    val bool = JsonCodecMaker.make[Boolean]

    override def decodeValue(in: JsonReader, default: SheetValueCell): SheetValueCell =
      (Try(in.readDouble()).orElse(Try(in.readBoolean()))).orElse(Try(in.readString(""))) match
        case Failure(exception) => throw exception
        case Success(value)     => value

    override def encodeValue(x: SheetValueCell, out: JsonWriter): Unit =
      x match
        case v: Double  => db.encodeValue(v, out)
        case v: Boolean => bool.encodeValue(v, out)
        case v: String  => str.encodeValue(v, out)

    override def nullValue: SheetValueCell = ""
  }

  extension (h: SheetValueRow) def toJson: Chunk[Json] = h.map(v => Json.writeToJson(v))
