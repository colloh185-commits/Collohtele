package telefam.company.desktop

import telefam.company.shared.ContactFilterTab
import telefam.company.shared.SharedFeedAction
import telefam.company.shared.SharedFeedTab
import telefam.company.shared.SharedHashtagItem
import telefam.company.shared.SharedPostFont
import telefam.company.shared.SharedPostMode
import telefam.company.shared.SharedSongTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DesktopSecurityGuard {
  fun isDebuggerAttached(): Boolean {
    return runCatching {
      val args = ManagementFactory.getRuntimeMXBean().inputArguments
      args.any { arg ->
        arg.contains("jdwp", ignoreCase = true) ||
          arg.contains("-Xdebug", ignoreCase = true) ||
          arg.contains("yjpagent", ignoreCase = true)
      }
    }.getOrDefault(false)
  }

  fun isAllowedServerUrl(rawUrl: String, enforceHttpsForRemote: Boolean = true): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return false
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (scheme == "https") return true
    if (scheme == "http") {
      val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
      return !enforceHttpsForRemote || isLoopback
    }
    return false
  }

  fun sha256Hex(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}

data class DesktopCountryDialOption(
  val iso: String,
  val name: String,
  val dialCode: String,
) {
  val flagEmoji: String
    get() = iso.uppercase().map { ch ->
      val codePoint = 0x1F1E6 + (ch.code - 'A'.code)
      String(Character.toChars(codePoint))
    }.joinToString("")
}

object DesktopCountryCatalog {
  val defaultCountry = DesktopCountryDialOption("KE", "Kenya", "+254")
  val allCountries: List<DesktopCountryDialOption> = listOf(
    DesktopCountryDialOption("AF", "Afghanistan", "+93"),
    DesktopCountryDialOption("AL", "Albania", "+355"),
    DesktopCountryDialOption("DZ", "Algeria", "+213"),
    DesktopCountryDialOption("AR", "Argentina", "+54"),
    DesktopCountryDialOption("AU", "Australia", "+61"),
    DesktopCountryDialOption("AT", "Austria", "+43"),
    DesktopCountryDialOption("BD", "Bangladesh", "+880"),
    DesktopCountryDialOption("BE", "Belgium", "+32"),
    DesktopCountryDialOption("BJ", "Benin", "+229"),
    DesktopCountryDialOption("BR", "Brazil", "+55"),
    DesktopCountryDialOption("BF", "Burkina Faso", "+226"),
    DesktopCountryDialOption("BI", "Burundi", "+257"),
    DesktopCountryDialOption("CM", "Cameroon", "+237"),
    DesktopCountryDialOption("CA", "Canada", "+1"),
    DesktopCountryDialOption("TD", "Chad", "+235"),
    DesktopCountryDialOption("CL", "Chile", "+56"),
    DesktopCountryDialOption("CN", "China", "+86"),
    DesktopCountryDialOption("CO", "Colombia", "+57"),
    DesktopCountryDialOption("CG", "Congo", "+242"),
    DesktopCountryDialOption("CD", "Congo (DRC)", "+243"),
    DesktopCountryDialOption("CI", "Cote d'Ivoire", "+225"),
    DesktopCountryDialOption("DK", "Denmark", "+45"),
    DesktopCountryDialOption("DJ", "Djibouti", "+253"),
    DesktopCountryDialOption("EG", "Egypt", "+20"),
    DesktopCountryDialOption("ET", "Ethiopia", "+251"),
    DesktopCountryDialOption("FI", "Finland", "+358"),
    DesktopCountryDialOption("FR", "France", "+33"),
    DesktopCountryDialOption("GA", "Gabon", "+241"),
    DesktopCountryDialOption("GM", "Gambia", "+220"),
    DesktopCountryDialOption("DE", "Germany", "+49"),
    DesktopCountryDialOption("GH", "Ghana", "+233"),
    DesktopCountryDialOption("GR", "Greece", "+30"),
    DesktopCountryDialOption("GN", "Guinea", "+224"),
    DesktopCountryDialOption("HT", "Haiti", "+509"),
    DesktopCountryDialOption("IN", "India", "+91"),
    DesktopCountryDialOption("ID", "Indonesia", "+62"),
    DesktopCountryDialOption("IE", "Ireland", "+353"),
    DesktopCountryDialOption("IL", "Israel", "+972"),
    DesktopCountryDialOption("IT", "Italy", "+39"),
    DesktopCountryDialOption("JM", "Jamaica", "+1876"),
    DesktopCountryDialOption("JP", "Japan", "+81"),
    DesktopCountryDialOption("JO", "Jordan", "+962"),
    DesktopCountryDialOption("KE", "Kenya", "+254"),
    DesktopCountryDialOption("KW", "Kuwait", "+965"),
    DesktopCountryDialOption("LB", "Lebanon", "+961"),
    DesktopCountryDialOption("LR", "Liberia", "+231"),
    DesktopCountryDialOption("LY", "Libya", "+218"),
    DesktopCountryDialOption("MG", "Madagascar", "+261"),
    DesktopCountryDialOption("MW", "Malawi", "+265"),
    DesktopCountryDialOption("MY", "Malaysia", "+60"),
    DesktopCountryDialOption("ML", "Mali", "+223"),
    DesktopCountryDialOption("MX", "Mexico", "+52"),
    DesktopCountryDialOption("MA", "Morocco", "+212"),
    DesktopCountryDialOption("MZ", "Mozambique", "+258"),
    DesktopCountryDialOption("NA", "Namibia", "+264"),
    DesktopCountryDialOption("NL", "Netherlands", "+31"),
    DesktopCountryDialOption("NZ", "New Zealand", "+64"),
    DesktopCountryDialOption("NE", "Niger", "+227"),
    DesktopCountryDialOption("NG", "Nigeria", "+234"),
    DesktopCountryDialOption("NO", "Norway", "+47"),
    DesktopCountryDialOption("PK", "Pakistan", "+92"),
    DesktopCountryDialOption("PE", "Peru", "+51"),
    DesktopCountryDialOption("PH", "Philippines", "+63"),
    DesktopCountryDialOption("PL", "Poland", "+48"),
    DesktopCountryDialOption("PT", "Portugal", "+351"),
    DesktopCountryDialOption("QA", "Qatar", "+974"),
    DesktopCountryDialOption("RO", "Romania", "+40"),
    DesktopCountryDialOption("RW", "Rwanda", "+250"),
    DesktopCountryDialOption("SA", "Saudi Arabia", "+966"),
    DesktopCountryDialOption("SN", "Senegal", "+221"),
    DesktopCountryDialOption("SL", "Sierra Leone", "+232"),
    DesktopCountryDialOption("SG", "Singapore", "+65"),
    DesktopCountryDialOption("SO", "Somalia", "+252"),
    DesktopCountryDialOption("ZA", "South Africa", "+27"),
    DesktopCountryDialOption("KR", "South Korea", "+82"),
    DesktopCountryDialOption("SS", "South Sudan", "+211"),
    DesktopCountryDialOption("ES", "Spain", "+34"),
    DesktopCountryDialOption("SD", "Sudan", "+249"),
    DesktopCountryDialOption("SE", "Sweden", "+46"),
    DesktopCountryDialOption("CH", "Switzerland", "+41"),
    DesktopCountryDialOption("TZ", "Tanzania", "+255"),
    DesktopCountryDialOption("TH", "Thailand", "+66"),
    DesktopCountryDialOption("TG", "Togo", "+228"),
    DesktopCountryDialOption("TN", "Tunisia", "+216"),
    DesktopCountryDialOption("TR", "Turkey", "+90"),
    DesktopCountryDialOption("UG", "Uganda", "+256"),
    DesktopCountryDialOption("UA", "Ukraine", "+380"),
    DesktopCountryDialOption("AE", "United Arab Emirates", "+971"),
    DesktopCountryDialOption("GB", "United Kingdom", "+44"),
    DesktopCountryDialOption("US", "United States", "+1"),
    DesktopCountryDialOption("ZM", "Zambia", "+260"),
    DesktopCountryDialOption("ZW", "Zimbabwe", "+263"),
  )

  fun isAtLeast13(isoDob: String): Boolean {
    val parsed = runCatching { LocalDate.parse(isoDob.trim()) }.getOrNull() ?: return false
    val today = LocalDate.now()
    if (parsed.isAfter(today)) return false
    return Period.between(parsed, today).years >= 13
  }
}

data class DesktopStoredSession(
  val installId: String,
  val serverBaseUrl: String,
  val accessToken: String = "",
  val refreshToken: String = "",
  val email: String = "",
  val suggestedFullName: String = "",
  val suggestedUsername: String = "",
  val profileCompleted: Boolean = false,
  val fullName: String = "",
  val username: String = "",
  val bio: String = "",
  val location: String = "",
  val themeMode: DesktopThemeMode = DesktopThemeMode.SYSTEM,
) {
  val isAuthenticated: Boolean
    get() = accessToken.isNotBlank() && !DesktopSecurityGuard.isDebuggerAttached()
}

class DesktopEncryptedSessionVault {
  private val homeDir = File(System.getProperty("user.home", "."), ".telefam").apply { mkdirs() }
  private val sessionFile = File(homeDir, "session.enc")
  private val keyFile = File(homeDir, "vault.key")
  private val aad = "telefam.company.desktop:vault:v2".toByteArray(StandardCharsets.UTF_8)

  private fun setOwnerOnlyPermissions(file: File) {
    runCatching {
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
  }

  private fun getOrCreateKey(): SecretKey {
    val keyBytes = if (keyFile.exists() && keyFile.length() == 32L) {
      keyFile.readBytes()
    } else {
      ByteArray(32).also { buf ->
        SecureRandom().nextBytes(buf)
        keyFile.writeBytes(buf)
        setOwnerOnlyPermissions(keyFile)
      }
    }
    val derived = MessageDigest.getInstance("SHA-256").digest(
      keyBytes + (System.getProperty("user.name", "desktop") + ":telefam").toByteArray(StandardCharsets.UTF_8),
    )
    return SecretKeySpec(derived, "AES")
  }

  fun load(): DesktopStoredSession {
    val envUrl = (System.getProperty("TELEFAM_API_BASE_URL")
      ?: System.getenv("TELEFAM_API_BASE_URL")
      ?: "")
      .trim()

    if (!sessionFile.exists() || DesktopSecurityGuard.isDebuggerAttached()) {
      return DesktopStoredSession(
        installId = UUID.randomUUID().toString(),
        serverBaseUrl = envUrl,
      )
    }

    return runCatching {
      val raw = sessionFile.readText(StandardCharsets.UTF_8).trim()
      val parts = raw.split(":", limit = 3)
      require(parts.size == 3 && parts[0] == "v2")
      val iv = Base64.getDecoder().decode(parts[1])
      val ct = Base64.getDecoder().decode(parts[2])
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
      cipher.updateAAD(aad)
      val json = String(cipher.doFinal(ct), StandardCharsets.UTF_8)
      val map = DesktopJson.parseObject(json)
      val savedUrl = DesktopJson.str(map, "server_base_url").ifBlank { envUrl }
      val mode = runCatching {
        DesktopThemeMode.valueOf(DesktopJson.str(map, "theme_mode").ifBlank { "SYSTEM" })
      }.getOrDefault(DesktopThemeMode.SYSTEM)

      DesktopStoredSession(
        installId = DesktopJson.str(map, "install_id").ifBlank { UUID.randomUUID().toString() },
        serverBaseUrl = if (envUrl.isNotBlank()) envUrl else savedUrl,
        accessToken = DesktopJson.str(map, "access_token"),
        refreshToken = DesktopJson.str(map, "refresh_token"),
        email = DesktopJson.str(map, "email"),
        suggestedFullName = DesktopJson.str(map, "suggested_full_name"),
        suggestedUsername = DesktopJson.str(map, "suggested_username"),
        profileCompleted = DesktopJson.bool(map, "profile_completed"),
        fullName = DesktopJson.str(map, "full_name"),
        username = DesktopJson.str(map, "username"),
        bio = DesktopJson.str(map, "bio"),
        location = DesktopJson.str(map, "location"),
        themeMode = mode,
      )
    }.getOrElse {
      DesktopStoredSession(
        installId = UUID.randomUUID().toString(),
        serverBaseUrl = envUrl,
      )
    }
  }

  fun save(session: DesktopStoredSession) {
    runCatching {
      val obj = buildString {
        append('{')
        append("\"install_id\":\"").append(DesktopJson.escape(session.installId)).append("\",")
        append("\"server_base_url\":\"").append(DesktopJson.escape(session.serverBaseUrl)).append("\",")
        append("\"access_token\":\"").append(DesktopJson.escape(session.accessToken)).append("\",")
        append("\"refresh_token\":\"").append(DesktopJson.escape(session.refreshToken)).append("\",")
        append("\"email\":\"").append(DesktopJson.escape(session.email)).append("\",")
        append("\"suggested_full_name\":\"").append(DesktopJson.escape(session.suggestedFullName)).append("\",")
        append("\"suggested_username\":\"").append(DesktopJson.escape(session.suggestedUsername)).append("\",")
        append("\"profile_completed\":").append(session.profileCompleted).append(',')
        append("\"full_name\":\"").append(DesktopJson.escape(session.fullName)).append("\",")
        append("\"username\":\"").append(DesktopJson.escape(session.username)).append("\",")
        append("\"bio\":\"").append(DesktopJson.escape(session.bio)).append("\",")
        append("\"location\":\"").append(DesktopJson.escape(session.location)).append("\",")
        append("\"theme_mode\":\"").append(session.themeMode.name).append('"')
        append('}')
      }
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
      cipher.updateAAD(aad)
      val encrypted = cipher.doFinal(obj.toByteArray(StandardCharsets.UTF_8))
      val payload = "v2:${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(encrypted)}"
      sessionFile.writeText(payload, StandardCharsets.UTF_8)
      setOwnerOnlyPermissions(sessionFile)
    }
  }
}

sealed interface DesktopApiOutcome<out T> {
  data class Ok<T>(val data: T) : DesktopApiOutcome<T>
  data class Fail(val statusCode: Int, val message: String) : DesktopApiOutcome<Nothing>
}

data class DesktopOtpRequestResult(
  val cooldownSeconds: Long,
)

data class DesktopMediaUploadResult(
  val mediaId: String,
  val contentId: String,
  val thumbnailMediaId: String,
  val perFrameHashes: List<String>,
)

data class DesktopContactsPage(
  val items: List<DesktopContactItem>,
  val page: Int,
  val hasMore: Boolean,
)

data class DesktopFeedPage(
  val items: List<DesktopFeedPost>,
  val page: Int,
  val hasMore: Boolean,
)

data class DesktopSearchShortcut(
  val key: String,
  val label: String,
  val count: Long,
)

data class DesktopFeedSearchPage(
  val shortcuts: List<DesktopSearchShortcut>,
  val items: List<DesktopFeedPost>,
  val hasMore: Boolean,
)

class DesktopApiClient(
  private val vault: DesktopEncryptedSessionVault,
  initialSession: DesktopStoredSession,
  private val onSessionUpdated: (DesktopStoredSession) -> Unit,
) {
  @Volatile
  var session: DesktopStoredSession = initialSession
    private set

  private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(12))
    .build()

  val isConfigured: Boolean
    get() = DesktopSecurityGuard.isAllowedServerUrl(session.serverBaseUrl)

  val deviceFingerprint: String
    get() {
      val os = System.getProperty("os.name", "desktop")
      val arch = System.getProperty("os.arch", "x64")
      val dbg = if (DesktopSecurityGuard.isDebuggerAttached()) "1" else "0"
      return DesktopSecurityGuard.sha256Hex("desktop|$os|$arch|$dbg|${session.installId}")
    }

  val ipHint: String
    get() = DesktopSecurityGuard.sha256Hex("desktop_client|${session.installId}")

  fun shareUrlForPost(postId: String): String {
    val clean = session.serverBaseUrl.trim().trimEnd('/')
    return if (DesktopSecurityGuard.isAllowedServerUrl(clean)) {
      "$clean/post/$postId"
    } else {
      "telefam://post/$postId"
    }
  }

  fun updateServerBaseUrl(newUrl: String) {
    val updated = session.copy(serverBaseUrl = newUrl.trim())
    session = updated
    vault.save(updated)
    onSessionUpdated(updated)
  }

  fun updateThemeMode(mode: DesktopThemeMode) {
    val updated = session.copy(themeMode = mode)
    session = updated
    vault.save(updated)
    onSessionUpdated(updated)
  }

  fun signOut() {
    val updated = session.copy(
      accessToken = "",
      refreshToken = "",
      profileCompleted = false,
      suggestedFullName = "",
      suggestedUsername = "",
    )
    session = updated
    vault.save(updated)
    onSessionUpdated(updated)
  }

  private fun baseUrl(): String? {
    val trimmed = session.serverBaseUrl.trim().trimEnd('/')
    return if (DesktopSecurityGuard.isAllowedServerUrl(trimmed)) trimmed else null
  }

  private fun applyAuthSessionResponse(map: Map<String, Any?>, fallbackEmail: String) {
    val email = DesktopJson.str(map, "email").ifBlank { fallbackEmail }
    val updated = session.copy(
      accessToken = DesktopJson.str(map, "session_token"),
      refreshToken = DesktopJson.str(map, "refresh_token"),
      email = email,
      suggestedFullName = DesktopJson.str(map, "suggested_full_name"),
      suggestedUsername = DesktopJson.str(map, "suggested_username"),
      profileCompleted = DesktopJson.bool(map, "profile_completed"),
    )
    session = updated
    vault.save(updated)
    onSessionUpdated(updated)
  }

  fun requestOtp(email: String): DesktopApiOutcome<DesktopOtpRequestResult> {
    val body = "{\"email\":\"${DesktopJson.escape(email.trim())}\"}"
    val resp = executeRaw("POST", "/v1/auth/otp/request", body.toByteArray(StandardCharsets.UTF_8), auth = false)
      ?: return DesktopApiOutcome.Fail(0, "Server connection unavailable")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    return DesktopApiOutcome.Ok(DesktopOtpRequestResult(DesktopJson.long(map, "cooldown_seconds", 60L)))
  }

  fun verifyOtp(email: String, otp: String): DesktopApiOutcome<DesktopStoredSession> {
    val body = "{\"email\":\"${DesktopJson.escape(email.trim())}\",\"otp\":\"${DesktopJson.escape(otp.trim())}\"}"
    val resp = executeRaw("POST", "/v1/auth/otp/verify", body.toByteArray(StandardCharsets.UTF_8), auth = false)
      ?: return DesktopApiOutcome.Fail(0, "Server connection unavailable")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    applyAuthSessionResponse(map, email.trim())
    return DesktopApiOutcome.Ok(session)
  }

  fun verifyLinkToken(token: String): DesktopApiOutcome<DesktopStoredSession> {
    val body = "{\"token\":\"${DesktopJson.escape(token.trim())}\"}"
    val resp = executeRaw("POST", "/v1/auth/verify-link", body.toByteArray(StandardCharsets.UTF_8), auth = false)
      ?: return DesktopApiOutcome.Fail(0, "Server connection unavailable")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    applyAuthSessionResponse(map, session.email)
    return DesktopApiOutcome.Ok(session)
  }

  fun verifyGoogleOAuth(identityToken: String): DesktopApiOutcome<DesktopStoredSession> {
    val body = "{\"provider\":\"google\",\"identity_token\":\"${DesktopJson.escape(identityToken.trim())}\"}"
    val resp = executeRaw("POST", "/v1/auth/oauth/google", body.toByteArray(StandardCharsets.UTF_8), auth = false)
      ?: return DesktopApiOutcome.Fail(0, "Server connection unavailable")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    applyAuthSessionResponse(map, session.email)
    return DesktopApiOutcome.Ok(session)
  }

  fun checkUsernameAvailable(username: String): Boolean? {
    val clean = URLEncoder.encode(username.trim().lowercase(), StandardCharsets.UTF_8)
    val resp = executeRaw("GET", "/v1/profile/check-username?u=$clean", null, auth = false) ?: return null
    if (resp.first !in 200..299) return null
    val map = DesktopJson.parseObject(resp.second)
    return DesktopJson.bool(map, "available")
  }

  fun uploadAvatar(bytes: ByteArray, cx: Float, cy: Float, scale: Float): DesktopApiOutcome<DesktopMediaUploadResult> {
    val path = "/v1/media/avatar?cx=$cx&cy=$cy&scale=$scale"
    val resp = executeRaw("POST", path, bytes, auth = true, contentType = "image/jpeg")
      ?: return DesktopApiOutcome.Fail(0, "Server connection unavailable")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    return DesktopApiOutcome.Ok(
      DesktopMediaUploadResult(
        mediaId = DesktopJson.str(map, "media_id"),
        contentId = DesktopJson.str(map, "content_id"),
        thumbnailMediaId = DesktopJson.str(map, "thumbnail_media_id"),
        perFrameHashes = DesktopJson.stringList(map, "per_frame_hashes"),
      ),
    )
  }

  fun setupProfile(
    fullName: String,
    username: String,
    phoneNumber: String,
    countryIso: String,
    gender: String,
    dateOfBirth: String,
    location: String,
    bio: String,
    avatarMediaId: String?,
  ): DesktopApiOutcome<DesktopStoredSession> {
    val body = buildString {
      append('{')
      append("\"full_name\":\"").append(DesktopJson.escape(fullName)).append("\",")
      append("\"username\":\"").append(DesktopJson.escape(username)).append("\",")
      append("\"phone_number\":\"").append(DesktopJson.escape(phoneNumber)).append("\",")
      append("\"country_iso\":\"").append(DesktopJson.escape(countryIso)).append("\",")
      append("\"gender\":\"").append(DesktopJson.escape(gender)).append("\",")
      append("\"date_of_birth\":\"").append(DesktopJson.escape(dateOfBirth)).append("\",")
      append("\"location\":\"").append(DesktopJson.escape(location)).append("\",")
      append("\"bio\":\"").append(DesktopJson.escape(bio)).append('"')
      if (!avatarMediaId.isNullOrBlank()) {
        append(",\"avatar_media_id\":\"").append(DesktopJson.escape(avatarMediaId)).append('"')
      }
      append('}')
    }
    val resp = executeRaw("POST", "/v1/profile/setup", body.toByteArray(StandardCharsets.UTF_8), auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Server connection unavailable")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val updated = session.copy(
      profileCompleted = true,
      fullName = fullName.trim(),
      username = username.trim().lowercase(),
      bio = bio.trim(),
      location = location.trim(),
    )
    session = updated
    vault.save(updated)
    onSessionUpdated(updated)
    return DesktopApiOutcome.Ok(updated)
  }

  fun listContacts(filter: ContactFilterTab, query: String, page: Int, pageSize: Int = 20): DesktopApiOutcome<DesktopContactsPage> {
    val q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
    val path = "/v1/contacts?filter=${filter.apiParam}&q=$q&page=$page&page_size=$pageSize"
    val resp = executeRaw("GET", path, null, auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    val rawItems = DesktopJson.objList(map, "items")
    val parsed = rawItems.mapNotNull { item ->
      val uid = DesktopJson.str(item, "user_id")
      if (uid.isBlank()) return@mapNotNull null
      DesktopContactItem(
        userId = uid,
        fullName = DesktopJson.str(item, "full_name"),
        username = DesktopJson.str(item, "username"),
        countryDisplay = DesktopJson.str(item, "location").ifBlank { DesktopJson.str(item, "country_iso") },
        isFollowing = DesktopJson.bool(item, "is_following"),
        followsYou = DesktopJson.bool(item, "follows_you"),
        riskScore = DesktopJson.double(item, "risk_score"),
      )
    }
    return DesktopApiOutcome.Ok(
      DesktopContactsPage(
        items = parsed,
        page = DesktopJson.long(map, "page", page.toLong()).toInt(),
        hasMore = DesktopJson.bool(map, "has_more"),
      ),
    )
  }

  fun setFollowContact(targetUserId: String, follow: Boolean): DesktopApiOutcome<Boolean> {
    val path = if (follow) "/v1/contacts/follow" else "/v1/contacts/unfollow"
    val body = "{\"target_user_id\":\"${DesktopJson.escape(targetUserId)}\"}"
    val resp = executeRaw("POST", path, body.toByteArray(StandardCharsets.UTF_8), auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first == 429) {
      return DesktopApiOutcome.Fail(429, "Try again later")
    }
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    return DesktopApiOutcome.Ok(DesktopJson.bool(map, "is_following"))
  }

  fun searchSongs(query: String): DesktopApiOutcome<List<SharedSongTrack>> {
    val q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
    val resp = executeRaw("GET", "/v1/songs/search?q=$q", null, auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    val tracks = DesktopJson.objList(map, "items").mapNotNull { item ->
      val title = DesktopJson.str(item, "title")
      val artist = DesktopJson.str(item, "artist")
      if (title.isBlank()) return@mapNotNull null
      SharedSongTrack(
        trackId = DesktopJson.str(item, "track_id"),
        title = title,
        artist = artist,
        album = DesktopJson.str(item, "album"),
        artworkUrl = DesktopJson.str(item, "artwork_url"),
        previewUrl = DesktopJson.str(item, "preview_url"),
        durationMs = DesktopJson.long(item, "duration_ms", 0L),
      )
    }
    return DesktopApiOutcome.Ok(tracks)
  }

  fun searchHashtags(query: String): DesktopApiOutcome<List<SharedHashtagItem>> {
    val q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
    val resp = executeRaw("GET", "/v1/hashtags/search?q=$q", null, auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    val tags = DesktopJson.objList(map, "items").mapNotNull { item ->
      val tag = DesktopJson.str(item, "tag")
      if (tag.isBlank()) return@mapNotNull null
      SharedHashtagItem(tag = tag, postCount = DesktopJson.long(item, "post_count", 0L))
    }
    return DesktopApiOutcome.Ok(tags)
  }

  fun uploadPostPhoto(bytes: ByteArray): DesktopApiOutcome<DesktopMediaUploadResult> {
    val resp = executeRaw("POST", "/v1/media/photo", bytes, auth = true, contentType = "image/jpeg")
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    return DesktopApiOutcome.Ok(
      DesktopMediaUploadResult(
        mediaId = DesktopJson.str(map, "media_id"),
        contentId = DesktopJson.str(map, "content_id"),
        thumbnailMediaId = "",
        perFrameHashes = emptyList(),
      ),
    )
  }

  fun uploadPostVideo(
    videoBytes: ByteArray,
    frameHashesCsv: String,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    width: Int,
    height: Int,
    thumbnailBytes: ByteArray?,
  ): DesktopApiOutcome<DesktopMediaUploadResult> {
    val boundary = "----TelefamDesktop${UUID.randomUUID().toString().replace("-", "")}"
    val baos = ByteArrayOutputStream()
    fun writeStr(s: String) = baos.write(s.toByteArray(StandardCharsets.UTF_8))

    writeStr("--$boundary\r\n")
    writeStr("Content-Disposition: form-data; name=\"video\"; filename=\"${UUID.randomUUID()}.mp4\"\r\n")
    writeStr("Content-Type: video/mp4\r\n\r\n")
    baos.write(videoBytes)
    writeStr("\r\n")
    if (thumbnailBytes != null && thumbnailBytes.isNotEmpty()) {
      writeStr("--$boundary\r\n")
      writeStr("Content-Disposition: form-data; name=\"thumbnail\"; filename=\"${UUID.randomUUID()}.jpg\"\r\n")
      writeStr("Content-Type: image/jpeg\r\n\r\n")
      baos.write(thumbnailBytes)
      writeStr("\r\n")
    }
    writeStr("--$boundary--\r\n")

    val path = "/v1/media/video?duration_ms=$durationMs&trim_start_ms=$trimStartMs&trim_end_ms=$trimEndMs&width=$width&height=$height"
    val resp = executeRaw(
      method = "POST",
      path = path,
      body = baos.toByteArray(),
      auth = true,
      contentType = "multipart/form-data; boundary=$boundary",
      extraHeaders = mapOf("X-Telefam-Frame-Hashes" to frameHashesCsv),
    ) ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    return DesktopApiOutcome.Ok(
      DesktopMediaUploadResult(
        mediaId = DesktopJson.str(map, "media_id"),
        contentId = DesktopJson.str(map, "content_id"),
        thumbnailMediaId = DesktopJson.str(map, "thumbnail_media_id"),
        perFrameHashes = DesktopJson.stringList(map, "per_frame_hashes"),
      ),
    )
  }

  fun createPost(
    clientOpId: String,
    mode: SharedPostMode,
    textContent: String,
    caption: String,
    themeId: String,
    fontId: String,
    song: SharedSongTrack?,
    taggedUserIds: List<String>,
    hashtags: List<String>,
    photoMediaIds: List<String>,
    videoMediaId: String,
    thumbnailMediaId: String,
    trimStartMs: Long,
    trimEndMs: Long,
    muteOriginalAudio: Boolean,
    contentId: String,
  ): DesktopApiOutcome<String> {
    val body = buildString {
      append('{')
      append("\"client_op_id\":\"").append(DesktopJson.escape(clientOpId)).append("\",")
      append("\"mode\":\"").append(mode.apiValue).append("\",")
      append("\"text_content\":\"").append(DesktopJson.escape(textContent)).append("\",")
      append("\"caption\":\"").append(DesktopJson.escape(caption)).append("\",")
      append("\"theme_id\":\"").append(DesktopJson.escape(themeId)).append("\",")
      append("\"font_id\":\"").append(DesktopJson.escape(fontId)).append("\",")
      if (song != null) {
        append("\"song\":{")
        append("\"track_id\":\"").append(DesktopJson.escape(song.trackId)).append("\",")
        append("\"title\":\"").append(DesktopJson.escape(song.title)).append("\",")
        append("\"artist\":\"").append(DesktopJson.escape(song.artist)).append("\",")
        append("\"album\":\"").append(DesktopJson.escape(song.album)).append("\",")
        append("\"artwork_url\":\"").append(DesktopJson.escape(song.artworkUrl)).append("\",")
        append("\"preview_url\":\"").append(DesktopJson.escape(song.previewUrl)).append("\",")
        append("\"duration_ms\":").append(song.durationMs)
        append("},")
      }
      append("\"tagged_user_ids\":[").append(taggedUserIds.joinToString(",") { "\"${DesktopJson.escape(it)}\"" }).append("],")
      append("\"hashtags\":[").append(hashtags.joinToString(",") { "\"${DesktopJson.escape(it)}\"" }).append("],")
      append("\"photo_media_ids\":[").append(photoMediaIds.joinToString(",") { "\"${DesktopJson.escape(it)}\"" }).append("],")
      append("\"video_media_id\":\"").append(DesktopJson.escape(videoMediaId)).append("\",")
      append("\"thumbnail_media_id\":\"").append(DesktopJson.escape(thumbnailMediaId)).append("\",")
      append("\"trim_start_ms\":").append(trimStartMs).append(',')
      append("\"trim_end_ms\":").append(trimEndMs).append(',')
      append("\"mute_original_audio\":").append(muteOriginalAudio).append(',')
      append("\"content_id\":\"").append(DesktopJson.escape(contentId)).append('"')
      append('}')
    }
    val resp = executeRaw("POST", "/v1/posts", body.toByteArray(StandardCharsets.UTF_8), auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    val postObj = DesktopJson.obj(map, "post")
    return DesktopApiOutcome.Ok(DesktopJson.str(postObj, "post_id"))
  }

  fun updatePost(
    postId: String,
    textContent: String,
    caption: String,
    themeId: String,
    fontId: String,
    song: SharedSongTrack?,
    hashtags: List<String>,
    muteOriginalAudio: Boolean,
  ): DesktopApiOutcome<Boolean> {
    val body = buildString {
      append('{')
      append("\"text_content\":\"").append(DesktopJson.escape(textContent)).append("\",")
      append("\"caption\":\"").append(DesktopJson.escape(caption)).append("\",")
      append("\"theme_id\":\"").append(DesktopJson.escape(themeId)).append("\",")
      append("\"font_id\":\"").append(DesktopJson.escape(fontId)).append("\",")
      if (song != null) {
        append("\"song\":{")
        append("\"track_id\":\"").append(DesktopJson.escape(song.trackId)).append("\",")
        append("\"title\":\"").append(DesktopJson.escape(song.title)).append("\",")
        append("\"artist\":\"").append(DesktopJson.escape(song.artist)).append("\",")
        append("\"album\":\"").append(DesktopJson.escape(song.album)).append("\",")
        append("\"artwork_url\":\"").append(DesktopJson.escape(song.artworkUrl)).append("\",")
        append("\"preview_url\":\"").append(DesktopJson.escape(song.previewUrl)).append("\",")
        append("\"duration_ms\":").append(song.durationMs)
        append("},")
      }
      append("\"hashtags\":[").append(hashtags.joinToString(",") { "\"${DesktopJson.escape(it)}\"" }).append("],")
      append("\"mute_original_audio\":").append(muteOriginalAudio)
      append('}')
    }
    val resp = executeRaw("PATCH", "/v1/posts/${URLEncoder.encode(postId, StandardCharsets.UTF_8)}", body.toByteArray(StandardCharsets.UTF_8), auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    return DesktopApiOutcome.Ok(true)
  }

  fun deletePost(postId: String): DesktopApiOutcome<Boolean> {
    val resp = executeRaw("DELETE", "/v1/posts/${URLEncoder.encode(postId, StandardCharsets.UTF_8)}", null, auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    return DesktopApiOutcome.Ok(true)
  }

  fun getFeeds(tab: SharedFeedTab, page: Int, pageSize: Int = 15): DesktopApiOutcome<DesktopFeedPage> {
    val resp = executeRaw("GET", "/v1/feeds?tab=${tab.apiParam}&page=$page&page_size=$pageSize", null, auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    return DesktopApiOutcome.Ok(
      DesktopFeedPage(
        items = DesktopJson.objList(map, "items").map(::parseFeedPost),
        page = DesktopJson.long(map, "page", page.toLong()).toInt(),
        hasMore = DesktopJson.bool(map, "has_more"),
      ),
    )
  }

  fun searchFeeds(query: String, shortcut: String, page: Int, pageSize: Int = 24): DesktopApiOutcome<DesktopFeedSearchPage> {
    val q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
    val sc = URLEncoder.encode(shortcut.trim(), StandardCharsets.UTF_8)
    val resp = executeRaw("GET", "/v1/feeds/search?q=$q&shortcut=$sc&page=$page&page_size=$pageSize", null, auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    val map = DesktopJson.parseObject(resp.second)
    val shortcuts = DesktopJson.objList(map, "shortcuts").map { s ->
      DesktopSearchShortcut(
        key = DesktopJson.str(s, "key"),
        label = DesktopJson.str(s, "label"),
        count = DesktopJson.long(s, "count", 0L),
      )
    }
    return DesktopApiOutcome.Ok(
      DesktopFeedSearchPage(
        shortcuts = shortcuts,
        items = DesktopJson.objList(map, "items").map(::parseFeedPost),
        hasMore = DesktopJson.bool(map, "has_more"),
      ),
    )
  }

  fun interactFeed(
    postId: String,
    action: SharedFeedAction,
    reason: String = "",
    watchMs: Long = 0L,
    durationMs: Long = 0L,
  ): DesktopApiOutcome<Boolean> {
    val body = buildString {
      append('{')
      append("\"client_op_id\":\"").append(UUID.randomUUID().toString()).append("\",")
      append("\"post_id\":\"").append(DesktopJson.escape(postId)).append("\",")
      append("\"action\":\"").append(action.apiValue).append("\",")
      append("\"watch_ms\":").append(watchMs).append(',')
      append("\"duration_ms\":").append(durationMs).append(',')
      append("\"reason\":\"").append(DesktopJson.escape(reason)).append('"')
      append('}')
    }
    val resp = executeRaw("POST", "/v1/feeds/interact", body.toByteArray(StandardCharsets.UTF_8), auth = true)
      ?: return DesktopApiOutcome.Fail(0, "Offline")
    if (resp.first !in 200..299) {
      return DesktopApiOutcome.Fail(resp.first, parseError(resp.first, resp.second))
    }
    return DesktopApiOutcome.Ok(true)
  }

  fun downloadFeedVideo(postId: String, destinationFile: File): DesktopApiOutcome<File> {
    val base = baseUrl() ?: return DesktopApiOutcome.Fail(0, "Server connection unavailable")
    return runCatching {
      val req = HttpRequest.newBuilder(URI("$base/v1/feeds/${URLEncoder.encode(postId, StandardCharsets.UTF_8)}/download"))
        .header("Authorization", "Bearer ${session.accessToken}")
        .header("X-Telefam-Device-FP", deviceFingerprint)
        .GET()
        .timeout(Duration.ofSeconds(60))
        .build()
      val res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
      if (res.statusCode() !in 200..299) {
        DesktopApiOutcome.Fail(res.statusCode(), "Download unavailable")
      } else {
        destinationFile.parentFile?.mkdirs()
        destinationFile.writeBytes(res.body())
        DesktopApiOutcome.Ok(destinationFile)
      }
    }.getOrElse { DesktopApiOutcome.Fail(0, "Network error") }
  }

  fun recordAuditEvent(action: String, metadata: Map<String, String> = emptyMap()) {
    val metaJson = metadata.entries.joinToString(",") {
      "\"${DesktopJson.escape(it.key)}\":\"${DesktopJson.escape(it.value)}\""
    }
    val body = "{\"action\":\"${DesktopJson.escape(action)}\",\"metadata\":{$metaJson}}"
    executeRaw("POST", "/v1/audit/event", body.toByteArray(StandardCharsets.UTF_8), auth = true)
  }

  private fun parseFeedPost(map: Map<String, Any?>): DesktopFeedPost {
    val modeStr = DesktopJson.str(map, "mode").lowercase()
    val mode = SharedPostMode.entries.firstOrNull { it.apiValue == modeStr } ?: SharedPostMode.VIDEO
    val fontStr = DesktopJson.str(map, "font_id").lowercase()
    val font = SharedPostFont.entries.firstOrNull { it.id == fontStr } ?: SharedPostFont.CLASSIC
    val songObj = DesktopJson.objOrNull(map, "song")
    val song = songObj?.let { s ->
      val title = DesktopJson.str(s, "title")
      if (title.isBlank()) null
      else SharedSongTrack(
        trackId = DesktopJson.str(s, "track_id"),
        title = title,
        artist = DesktopJson.str(s, "artist"),
        album = DesktopJson.str(s, "album"),
        artworkUrl = DesktopJson.str(s, "artwork_url"),
        previewUrl = DesktopJson.str(s, "preview_url"),
        durationMs = DesktopJson.long(s, "duration_ms", 0L),
      )
    }
    val rawLabel = DesktopJson.str(map, "recommendation_label")
    return DesktopFeedPost(
      postId = DesktopJson.str(map, "post_id"),
      authorUserId = DesktopJson.str(map, "author_user_id"),
      authorUsername = DesktopJson.str(map, "author_username"),
      authorFullName = DesktopJson.str(map, "author_full_name"),
      isFollowingAuthor = DesktopJson.bool(map, "is_following_author"),
      isOwner = DesktopJson.bool(map, "is_owner"),
      mode = mode,
      textContent = DesktopJson.str(map, "text_content"),
      caption = DesktopJson.str(map, "caption"),
      themeId = DesktopJson.str(map, "theme_id").ifBlank { "crimson" },
      fontOption = font,
      song = song,
      hashtags = DesktopJson.stringList(map, "hashtags"),
      mediaWidth = DesktopJson.long(map, "width", 1080L).toInt().coerceAtLeast(1),
      mediaHeight = DesktopJson.long(map, "height", 1920L).toInt().coerceAtLeast(1),
      likesCount = DesktopJson.long(map, "likes_count", 0L),
      commentsCount = DesktopJson.long(map, "comments_count", 0L),
      sharesCount = DesktopJson.long(map, "shares_count", 0L),
      favouritesCount = DesktopJson.long(map, "favourites_count", 0L),
      repostsCount = DesktopJson.long(map, "reposts_count", 0L),
      isLiked = DesktopJson.bool(map, "is_liked"),
      isFavourited = DesktopJson.bool(map, "is_favourited"),
      isReposted = DesktopJson.bool(map, "is_reposted"),
      repostedByUsername = DesktopJson.str(map, "reposted_by_username"),
      recommendationLabel = rawLabel,
      muteOriginalAudio = DesktopJson.bool(map, "mute_original_audio"),
    )
  }

  private fun parseError(status: Int, body: String): String {
    val map = runCatching { DesktopJson.parseObject(body) }.getOrNull()
    val err = map?.let { DesktopJson.str(it, "error") }.orEmpty()
    if (err.isNotBlank()) return err
    return if (status == 429) "Try again later" else "Unable to complete request ($status)"
  }

  private fun executeRaw(
    method: String,
    path: String,
    body: ByteArray?,
    auth: Boolean,
    contentType: String = "application/json",
    extraHeaders: Map<String, String> = emptyMap(),
    allowRefreshRetry: Boolean = true,
  ): Pair<Int, String>? {
    if (DesktopSecurityGuard.isDebuggerAttached()) {
      return 403 to "{\"error\":\"Security verification failed\"}"
    }
    val base = baseUrl() ?: return null
    val fullUrl = "$base$path"
    val builder = HttpRequest.newBuilder(URI(fullUrl))
      .timeout(Duration.ofSeconds(30))
      .header("X-Telefam-Device-FP", deviceFingerprint)
      .header("X-Telefam-Client-IP-Hint", ipHint)
    if (auth && session.accessToken.isNotBlank()) {
      builder.header("Authorization", "Bearer ${session.accessToken}")
    }
    extraHeaders.forEach { (k, v) -> builder.header(k, v) }
    when (method) {
      "GET" -> builder.GET()
      "DELETE" -> builder.DELETE()
      else -> {
        builder.header("Content-Type", contentType)
        val pub = HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0))
        builder.method(method, pub)
      }
    }
    val response = runCatching {
      httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }.getOrNull() ?: return null

    if (response.statusCode() == 401 && auth && allowRefreshRetry && session.refreshToken.isNotBlank()) {
      if (attemptRefreshToken()) {
        return executeRaw(method, path, body, auth, contentType, extraHeaders, allowRefreshRetry = false)
      }
    }
    return response.statusCode() to response.body()
  }

  private fun attemptRefreshToken(): Boolean {
    val base = baseUrl() ?: return false
    val rt = session.refreshToken
    if (rt.isBlank()) return false
    val body = "{\"refresh_token\":\"${DesktopJson.escape(rt)}\"}"
    val req = HttpRequest.newBuilder(URI("$base/v1/auth/token/refresh"))
      .timeout(Duration.ofSeconds(15))
      .header("Content-Type", "application/json")
      .header("X-Telefam-Device-FP", deviceFingerprint)
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()
    val res = runCatching {
      httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }.getOrNull() ?: return false
    if (res.statusCode() !in 200..299) {
      signOut()
      return false
    }
    val map = DesktopJson.parseObject(res.body())
    val nextAccess = DesktopJson.str(map, "session_token")
    val nextRefresh = DesktopJson.str(map, "refresh_token")
    if (nextAccess.isBlank() || nextRefresh.isBlank()) return false
    val updated = session.copy(accessToken = nextAccess, refreshToken = nextRefresh)
    session = updated
    vault.save(updated)
    onSessionUpdated(updated)
    return true
  }
}

/** Minimal zero-dependency RFC 8259 JSON parser & escaper for Desktop KMP JVM. */
object DesktopJson {
  fun escape(raw: String): String = buildString(raw.length + 8) {
    for (ch in raw) {
      when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> append(ch)
      }
    }
  }

  fun str(map: Map<String, Any?>, key: String): String = (map[key] as? String).orEmpty()
  fun bool(map: Map<String, Any?>, key: String): Boolean = map[key] as? Boolean ?: false
  fun long(map: Map<String, Any?>, key: String, default: Long = 0L): Long =
    (map[key] as? Number)?.toLong() ?: default
  fun double(map: Map<String, Any?>, key: String, default: Double = 0.0): Double =
    (map[key] as? Number)?.toDouble() ?: default

  @Suppress("UNCHECKED_CAST")
  fun obj(map: Map<String, Any?>, key: String): Map<String, Any?> =
    (map[key] as? Map<String, Any?>).orEmpty()

  @Suppress("UNCHECKED_CAST")
  fun objOrNull(map: Map<String, Any?>, key: String): Map<String, Any?>? =
    map[key] as? Map<String, Any?>

  @Suppress("UNCHECKED_CAST")
  fun objList(map: Map<String, Any?>, key: String): List<Map<String, Any?>> =
    (map[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()

  fun stringList(map: Map<String, Any?>, key: String): List<String> =
    (map[key] as? List<*>)?.mapNotNull { it as? String }.orEmpty()

  @Suppress("UNCHECKED_CAST")
  fun parseObject(json: String): Map<String, Any?> {
    val parser = Parser(json)
    return (parser.parseValue() as? Map<String, Any?>).orEmpty()
  }

  private class Parser(private val src: String) {
    private var idx = 0

    private fun skipWs() {
      while (idx < src.length && src[idx].isWhitespace()) idx++
    }

    fun parseValue(): Any? {
      skipWs()
      if (idx >= src.length) return null
      return when (src[idx]) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> parseString()
        't', 'f' -> parseBoolean()
        'n' -> parseNull()
        else -> parseNumber()
      }
    }

    private fun parseObject(): Map<String, Any?> {
      val map = linkedMapOf<String, Any?>()
      idx++ // '{'
      skipWs()
      if (idx < src.length && src[idx] == '}') {
        idx++
        return map
      }
      while (idx < src.length) {
        skipWs()
        val key = parseString()
        skipWs()
        if (idx < src.length && src[idx] == ':') idx++
        val value = parseValue()
        map[key] = value
        skipWs()
        if (idx < src.length && src[idx] == ',') {
          idx++
        } else {
          if (idx < src.length && src[idx] == '}') idx++
          break
        }
      }
      return map
    }

    private fun parseArray(): List<Any?> {
      val list = mutableListOf<Any?>()
      idx++ // '['
      skipWs()
      if (idx < src.length && src[idx] == ']') {
        idx++
        return list
      }
      while (idx < src.length) {
        list.add(parseValue())
        skipWs()
        if (idx < src.length && src[idx] == ',') {
          idx++
        } else {
          if (idx < src.length && src[idx] == ']') idx++
          break
        }
      }
      return list
    }

    private fun parseString(): String {
      if (idx >= src.length || src[idx] != '"') return ""
      idx++
      val sb = StringBuilder()
      while (idx < src.length) {
        val ch = src[idx++]
        if (ch == '"') break
        if (ch == '\\' && idx < src.length) {
          when (val esc = src[idx++]) {
            '"', '\\', '/' -> sb.append(esc)
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'u' -> {
              if (idx + 4 <= src.length) {
                val hex = src.substring(idx, idx + 4)
                sb.append(hex.toInt(16).toChar())
                idx += 4
              }
            }
            else -> sb.append(esc)
          }
        } else {
          sb.append(ch)
        }
      }
      return sb.toString()
    }

    private fun parseBoolean(): Boolean {
      return if (src.startsWith("true", idx)) {
        idx += 4
        true
      } else {
        idx += 5
        false
      }
    }

    private fun parseNull(): Any? {
      idx += 4
      return null
    }

    private fun parseNumber(): Number {
      val start = idx
      while (idx < src.length && (src[idx].isDigit() || src[idx] in listOf('-', '+', '.', 'e', 'E'))) {
        idx++
      }
      val slice = src.substring(start, idx)
      return if (slice.contains('.') || slice.contains('e') || slice.contains('E')) {
        slice.toDoubleOrNull() ?: 0.0
      } else {
        slice.toLongOrNull() ?: 0L
      }
    }
  }
}
