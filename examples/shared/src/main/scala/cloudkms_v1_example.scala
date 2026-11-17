import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

import zio.*
import zio.ZIO.logInfo

import com.anymindgroup.gcp.cloudkms.v1.resources.projects.locations.key_rings.CryptoKeys
import com.anymindgroup.gcp.cloudkms.v1.schemas.{DecryptRequest, EncryptRequest}

// Encrypts and decrypts data with an existing symmetric (ENCRYPT_DECRYPT) Cloud KMS key.
//
// Authenticate with Application Default Credentials:
// gcloud auth application-default login
//
// Run with: sbt "examplesJVM/runMain cloudkms_v1_example"
// Requires environment variables GCP_PROJECT_ID, KMS_LOCATION, KMS_KEY_RING and KMS_CRYPTO_KEY to be set.
object cloudkms_v1_example extends ZIOAppDefault:
  private def env(name: String) =
    System.env(name).someOrFail(new RuntimeException(s"$name env var is required"))

  def run =
    for
      projectId <- env("GCP_PROJECT_ID")
      location  <- env("KMS_LOCATION")
      keyRing   <- env("KMS_KEY_RING")
      cryptoKey <- env("KMS_CRYPTO_KEY")
      backend   <- com.anymindgroup.gcp.auth.defaultAccessTokenBackend()

      // 1. Encrypt (plaintext is sent as base64 encoded bytes)
      plaintext   = "Hello from zio-gcp!"
      ciphertext <- backend
                      .send(
                        CryptoKeys.encrypt(
                          projectsId = projectId,
                          locationsId = location,
                          keyRingsId = keyRing,
                          cryptoKeysId = cryptoKey,
                          request =
                            EncryptRequest(plaintext = Base64.getEncoder.encodeToString(plaintext.getBytes(UTF_8))),
                        )
                      )
                      .flatMap: res =>
                        ZIO.fromEither(
                          res.body.flatMap(_.ciphertext.toRight(new RuntimeException("Missing ciphertext")))
                        )
      _ <- logInfo(s"Encrypted '$plaintext' to (base64) ciphertext: $ciphertext")

      // 2. Decrypt
      decrypted <- backend
                     .send(
                       CryptoKeys.decrypt(
                         projectsId = projectId,
                         locationsId = location,
                         keyRingsId = keyRing,
                         cryptoKeysId = cryptoKey,
                         request = DecryptRequest(ciphertext = ciphertext),
                       )
                     )
                     .flatMap: res =>
                       ZIO.fromEither(res.body.flatMap(_.plaintext.toRight(new RuntimeException("Missing plaintext"))))
                     .map(b64 => String(Base64.getDecoder.decode(b64), UTF_8))
      _ <- logInfo(s"Decrypted ciphertext to: '$decrypted'")
    yield ()
