import zio.*, zio.ZIO.{logInfo, logError}
import com.anymindgroup.gcp.sheets.toWriteValueRange
import com.anymindgroup.gcp.sheets.v4.resources.*
import com.anymindgroup.gcp.sheets.v4.schemas.{Spreadsheet, SpreadsheetProperties}
import sttp.model.Header

// add https://www.googleapis.com/auth/spreadsheets scope to application default credentials
// gcloud auth application-default login --scopes=openid,https://www.googleapis.com/auth/userinfo.email,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/sqlservice.login,https://www.googleapis.com/auth/spreadsheets
object sheets_example extends ZIOAppDefault:
  def run =
    for
      backend <- com.anymindgroup.gcp.auth.defaultAccessTokenBackend()
      // for use with user credentials a quota project is required:
      // https://docs.cloud.google.com/docs/authentication/troubleshoot-adc#user-creds-client-based
      // https://docs.cloud.google.com/docs/authentication/rest#set-billing-project
      xGoogUserProject = Header("x-goog-user-project", "my-gcp-project")

      // Create a spreadsheet with a title
      createReq = Spreadsheet(properties = Some(SpreadsheetProperties(title = Some("zio-gcp example spreadsheet"))))
      sheet    <- backend
                 .send(Spreadsheets.create(createReq).header(xGoogUserProject))
                 .flatMap: res =>
                   ZIO.fromEither:
                     for
                       spreadsheet <- res.body.left.map(_.getMessage())
                       id          <- spreadsheet.spreadsheetId.toRight("Missing spreadsheet id")
                       title       <- spreadsheet.sheets
                                  .flatMap(_.headOption)
                                  .flatMap(_.properties.flatMap(_.title))
                                  .toRight("Missing first sheet title")
                       url <- spreadsheet.spreadsheetUrl.toRight("Missing spreadsheet url")
                     yield (
                       id = id,
                       firstSheet = title,
                       url = url,
                     )

      _ <- logInfo(s"Created spreadsheet: ${sheet.url} (id=${sheet.id})")

      // write values to the first sheet
      values = toWriteValueRange(
                 Chunk(
                   Chunk("Name", "Age", "City", "Over 18"),
                   Chunk("Alice", 30, "New York", true),
                   Chunk("Bob", 17, "San Francisco", false),
                 )
               )
      _ <- backend
             .send(
               spreadsheets.Values
                 .update(
                   spreadsheetId = sheet.id,
                   range = sheet.firstSheet,
                   request = values,
                   valueInputOption = Some("RAW"),
                 )
                 .header(xGoogUserProject)
             )
             .flatMap:
               _.body match
                 case Right(body) => logInfo(s"Wrote values to spreadsheet: ${body}")
                 case Left(err)   => logError(s"Failed to write values: $err")
    yield ()
