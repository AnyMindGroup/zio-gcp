//> using scala 3.7.4
//> using dep com.anymindgroup::zio-gcp-auth::0.2.7
//> using dep com.anymindgroup::zio-gcp-storage::0.2.7

import zio.*, zio.ZIO.{logInfo, logError, dieMessage}
import com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.sheets.v4.resources.Spreadsheets
import com.anymindgroup.gcp.sheets.v4.resources.spreadsheets.Values
import com.anymindgroup.gcp.sheets.v4.schemas.{Spreadsheet, SpreadsheetProperties, ValueRange}
import sttp.model.Header, sttp.client4.Response

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
                 values = Some(
                   Chunk(
                     Chunk("Name", "Age", "City"),
                     Chunk("Alice", "30", "New York"),
                     Chunk("Bob", "25", "San Francisco"),
                   )
                 )
               )
      _ <- backend
             .send(
               Values
                 .update(
                   spreadsheetId = id,
                   range = "Sheet1!A1:C3",
                   request = values,
                   valueInputOption = Some("RAW"),
                 )
                 .header(xGoogUserProject)
             )
             .flatMap:
               case Response(body = Right(body)) => logInfo(s"Wrote values to spreadsheet: ${body}")
               case Response(body = Left(err))   => logError(s"Failed to write values: $err")
    yield ()
