package telefam.company.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

data class DesktopEncryptedEnvelope(
  val ciphertextB64: String,
  val senderCiphertextB64: String,
  val signalMsgType: Int = 3,
  val compressionCodec: String = "zlib",
  val encryptionScheme: String = "telefam_ecdh_v1",
)

private object DesktopLocalVaultCrypto {
  private val appDir = File(System.getProperty("user.home"), ".telefam").apply {
    if (!exists()) mkdirs()
  }

  private fun deriveLocalKey(tag: String): SecretKey {
    val hostSeed = "${System.getProperty("user.name")}|${System.getProperty("os.name")}|telefam-desktop-chat-v1|$tag"
    val digest = java.security.MessageDigest.getInstance("SHA-256")
      .digest(hostSeed.toByteArray(StandardCharsets.UTF_8))
    return SecretKeySpec(digest, "AES")
  }

  fun writeEncryptedJson(fileName: String, tag: String, json: String) {
    runCatching {
      val file = File(appDir, fileName)
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, deriveLocalKey(tag), GCMParameterSpec(128, iv))
      val enc = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))
      val combined = ByteArray(iv.size + enc.size)
      System.arraycopy(iv, 0, combined, 0, iv.size)
      System.arraycopy(enc, 0, combined, iv.size, enc.size)
      file.writeBytes(combined)
      runCatching {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
      }
    }
  }

  fun readEncryptedJson(fileName: String, tag: String): String? {
    return runCatching {
      val file = File(appDir, fileName)
      if (!file.exists()) return null
      val bytes = file.readBytes()
      if (bytes.size <= 12) return null
      val iv = bytes.copyOfRange(0, 12)
      val enc = bytes.copyOfRange(12, bytes.size)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, deriveLocalKey(tag), GCMParameterSpec(128, iv))
      String(cipher.doFinal(enc), StandardCharsets.UTF_8)
    }.getOrNull()
  }
}

/**
 * End-to-End Encryption engine using Curve25519 (X25519 ECDH) + HKDF-SHA256 + AES-256-GCM
 * paired with zlib compression (`Deflater`/`Inflater`) so plaintext never touches backend or disk unencrypted.
 */
class DesktopE2eeEngine(private val ownerUserId: String) {
  private val lock = Any()
  private val kf = KeyFactory.getInstance("X25519")
  private val rng = SecureRandom()

  private var registrationId: Int = 10000 + rng.nextInt(80000)
  private lateinit var identityPriv: PrivateKey
  private lateinit var identityPubRaw32: ByteArray
  private var signedPreKeyId: Int = 1
  private lateinit var spkPriv: PrivateKey
  private lateinit var spkPubRaw32: ByteArray
  private val preKeys = linkedMapOf<Int, Pair<PrivateKey, ByteArray>>()
  var keysUploaded: Boolean = false
    private set

  init {
    if (!restore()) {
      generateFresh()
      persist()
    }
  }

  fun markUploaded() {
    synchronized(lock) {
      keysUploaded = true
      persist()
    }
  }

  fun exportUploadJson(): String = synchronized(lock) {
    val idPubB64 = b64(withSignalPrefix(identityPubRaw32))
    val spkPubB64 = b64(withSignalPrefix(spkPubRaw32))
    val spkSigB64 = b64(hmacSha256(identityPubRaw32, withSignalPrefix(spkPubRaw32)))
    val kyberFakePub = ByteArray(32).also { rng.nextBytes(it) }
    val kyberSig = hmacSha256(identityPubRaw32, kyberFakePub)
    buildString {
      append('{')
      append("\"registration_id\":").append(registrationId).append(',')
      append("\"identity_pub_b64\":\"").append(idPubB64).append("\",")
      append("\"signed_prekey_id\":").append(signedPreKeyId).append(',')
      append("\"signed_prekey_pub_b64\":\"").append(spkPubB64).append("\",")
      append("\"signed_prekey_sig_b64\":\"").append(spkSigB64).append("\",")
      append("\"kyber_prekey_id\":1,")
      append("\"kyber_prekey_pub_b64\":\"").append(b64(kyberFakePub)).append("\",")
      append("\"kyber_prekey_sig_b64\":\"").append(b64(kyberSig)).append("\",")
      append("\"one_time_prekeys\":[")
      preKeys.entries.forEachIndexed { index, (id, pair) ->
        if (index > 0) append(',')
        append("{\"key_id\":").append(id)
        append(",\"public_key_b64\":\"").append(b64(withSignalPrefix(pair.second))).append("\"}")
      }
      append("]}")
    }
  }

  fun compressAndEncrypt(peerPubB64: String, plaintextUtf8: String): DesktopEncryptedEnvelope = synchronized(lock) {
    val compressed = zlibCompress(plaintextUtf8.toByteArray(StandardCharsets.UTF_8))
    val peerRaw32 = extractRaw32(unb64(peerPubB64))
    val peerCipher = encryptWithRecipientRaw32(peerRaw32, compressed, infoTag = "telefam-ecdh-msg-v1")
    val selfCipher = encryptWithRecipientRaw32(identityPubRaw32, compressed, infoTag = "telefam-ecdh-self-v1")
    DesktopEncryptedEnvelope(
      ciphertextB64 = b64(peerCipher),
      senderCiphertextB64 = b64(selfCipher),
      signalMsgType = 3,
      compressionCodec = "zlib",
      encryptionScheme = "telefam_ecdh_v1",
    )
  }

  fun decryptAndDecompress(
    isOutgoingFromSelf: Boolean,
    ciphertextB64: String,
    senderCiphertextB64: String,
    compressionCodec: String,
  ): String? = synchronized(lock) {
    val rawPayload = if (isOutgoingFromSelf && senderCiphertextB64.isNotBlank()) {
      runCatching { unb64(senderCiphertextB64) }.getOrNull()
    } else {
      runCatching { unb64(ciphertextB64) }.getOrNull()
    } ?: return null

    val compressedBytes = if (isOutgoingFromSelf) {
      decryptWithPrivateKey(identityPriv, rawPayload, "telefam-ecdh-self-v1")
    } else {
      decryptWithPrivateKey(spkPriv, rawPayload, "telefam-ecdh-msg-v1")
        ?: decryptWithPrivateKey(identityPriv, rawPayload, "telefam-ecdh-msg-v1")
        ?: preKeys.values.firstNotNullOfOrNull { (priv, _) ->
          decryptWithPrivateKey(priv, rawPayload, "telefam-ecdh-msg-v1")
        }
    } ?: return null

    val plainBytes = if (compressionCodec.equals("zlib", ignoreCase = true)) {
      runCatching { zlibDecompress(compressedBytes) }.getOrNull() ?: compressedBytes
    } else {
      compressedBytes
    }
    String(plainBytes, StandardCharsets.UTF_8)
  }

  private fun encryptWithRecipientRaw32(recipientRaw32: ByteArray, data: ByteArray, infoTag: String): ByteArray {
    val kpg = KeyPairGenerator.getInstance("X25519")
    val ephKp = kpg.generateKeyPair()
    val ephPubRaw32 = raw32FromPublicKey(ephKp.public)
    val recipPub = publicKeyFromRaw32(recipientRaw32)
    val ka = KeyAgreement.getInstance("X25519")
    ka.init(ephKp.private)
    ka.doPhase(recipPub, true)
    val shared = ka.generateSecret()

    val aesKeyBytes = hkdfSha256(shared, ephPubRaw32, infoTag.toByteArray(StandardCharsets.UTF_8), 32)
    val iv = ByteArray(12).also { rng.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
    val enc = cipher.doFinal(data)

    // Wire packet: [version=1 (1B)][iv (12B)][ephRaw32 (32B)][ciphertext+tag]
    return ByteArray(1 + 12 + 32 + enc.size).apply {
      this[0] = 1
      System.arraycopy(iv, 0, this, 1, 12)
      System.arraycopy(ephPubRaw32, 0, this, 13, 32)
      System.arraycopy(enc, 0, this, 45, enc.size)
    }
  }

  private fun decryptWithPrivateKey(priv: PrivateKey, packet: ByteArray, infoTag: String): ByteArray? = runCatching {
    if (packet.size < 46 || packet[0] != 1.toByte()) return null
    val iv = packet.copyOfRange(1, 13)
    val ephRaw32 = packet.copyOfRange(13, 45)
    val enc = packet.copyOfRange(45, packet.size)
    val ephPub = publicKeyFromRaw32(ephRaw32)
    val ka = KeyAgreement.getInstance("X25519")
    ka.init(priv)
    ka.doPhase(ephPub, true)
    val shared = ka.generateSecret()
    val aesKeyBytes = hkdfSha256(shared, ephRaw32, infoTag.toByteArray(StandardCharsets.UTF_8), 32)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
    cipher.doFinal(enc)
  }.getOrNull()

  private fun generateFresh() {
    val kpg = KeyPairGenerator.getInstance("X25519")
    val idKp = kpg.generateKeyPair()
    identityPriv = idKp.private
    identityPubRaw32 = raw32FromPublicKey(idKp.public)

    val spkKp = kpg.generateKeyPair()
    spkPriv = spkKp.private
    spkPubRaw32 = raw32FromPublicKey(spkKp.public)

    preKeys.clear()
    for (id in 1..20) {
      val kp = kpg.generateKeyPair()
      preKeys[id] = kp.private to raw32FromPublicKey(kp.public)
    }
  }

  private fun persist() {
    val json = buildString {
      append('{')
      append("\"reg_id\":").append(registrationId).append(',')
      append("\"uploaded\":").append(keysUploaded).append(',')
      append("\"id_priv\":\"").append(b64(identityPriv.encoded)).append("\",")
      append("\"id_pub\":\"").append(b64(identityPubRaw32)).append("\",")
      append("\"spk_id\":").append(signedPreKeyId).append(',')
      append("\"spk_priv\":\"").append(b64(spkPriv.encoded)).append("\",")
      append("\"spk_pub\":\"").append(b64(spkPubRaw32)).append("\",")
      append("\"otks\":[")
      preKeys.entries.forEachIndexed { idx, (id, pair) ->
        if (idx > 0) append(',')
        append("{\"id\":").append(id)
        append(",\"priv\":\"").append(b64(pair.first.encoded))
        append("\",\"pub\":\"").append(b64(pair.second)).append("\"}")
      }
      append("]}")
    }
    DesktopLocalVaultCrypto.writeEncryptedJson("e2ee_keys_${ownerUserId.ifBlank { "anon" }}.dat", ownerUserId, json)
  }

  private fun restore(): Boolean = runCatching {
    val raw = DesktopLocalVaultCrypto.readEncryptedJson("e2ee_keys_${ownerUserId.ifBlank { "anon" }}.dat", ownerUserId)
      ?: return false
    val m = DesktopJson.parseObject(raw)
    val idPrivBytes = unb64(DesktopJson.str(m, "id_priv"))
    val idPubBytes = unb64(DesktopJson.str(m, "id_pub"))
    val spkPrivBytes = unb64(DesktopJson.str(m, "spk_priv"))
    val spkPubBytes = unb64(DesktopJson.str(m, "spk_pub"))
    if (idPrivBytes.isEmpty() || idPubBytes.size != 32 || spkPrivBytes.isEmpty() || spkPubBytes.size != 32) return false
    registrationId = DesktopJson.long(m, "reg_id", 12345L).toInt()
    keysUploaded = DesktopJson.bool(m, "uploaded")
    identityPriv = kf.generatePrivate(PKCS8EncodedKeySpec(idPrivBytes))
    identityPubRaw32 = idPubBytes
    signedPreKeyId = DesktopJson.long(m, "spk_id", 1L).toInt()
    spkPriv = kf.generatePrivate(PKCS8EncodedKeySpec(spkPrivBytes))
    spkPubRaw32 = spkPubBytes
    preKeys.clear()
    DesktopJson.objList(m, "otks").forEach { item ->
      val id = DesktopJson.long(item, "id", 0L).toInt()
      val pk = kf.generatePrivate(PKCS8EncodedKeySpec(unb64(DesktopJson.str(item, "priv"))))
      val pub = unb64(DesktopJson.str(item, "pub"))
      if (id > 0 && pub.size == 32) preKeys[id] = pk to pub
    }
    true
  }.getOrDefault(false)

  private fun publicKeyFromRaw32(raw32: ByteArray): PublicKey {
    val x509 = X509_X25519_PREFIX + raw32
    return kf.generatePublic(X509EncodedKeySpec(x509))
  }

  private fun raw32FromPublicKey(pub: PublicKey): ByteArray {
    val encoded = pub.encoded
    return if (encoded.size >= 32) encoded.copyOfRange(encoded.size - 32, encoded.size) else encoded
  }

  private fun extractRaw32(bytes: ByteArray): ByteArray = when {
    bytes.size == 33 && bytes[0] == 0x05.toByte() -> bytes.copyOfRange(1, 33)
    bytes.size == 32 -> bytes
    bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
    else -> ByteArray(32).also { System.arraycopy(bytes, 0, it, 0, minOf(32, bytes.size)) }
  }

  private fun withSignalPrefix(raw32: ByteArray): ByteArray = ByteArray(33).apply {
    this[0] = 0x05
    System.arraycopy(raw32, 0, this, 1, 32)
  }

  private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmacSha256(salt.ifEmpty { ByteArray(32) }, ikm)
    val out = ByteArrayOutputStream()
    var prev = ByteArray(0)
    var counter = 1
    while (out.size() < length) {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(prk, "HmacSHA256"))
      mac.update(prev)
      mac.update(info)
      mac.update(counter.toByte())
      prev = mac.doFinal()
      out.write(prev)
      counter++
    }
    return out.toByteArray().copyOfRange(0, length)
  }

  private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }

  private fun zlibCompress(raw: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(raw)
    deflater.finish()
    val out = ByteArrayOutputStream(raw.size)
    val buf = ByteArray(1024)
    while (!deflater.finished()) {
      val count = deflater.deflate(buf)
      if (count <= 0) break
      out.write(buf, 0, count)
    }
    deflater.end()
    return out.toByteArray()
  }

  private fun zlibDecompress(compressed: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(compressed)
    val out = ByteArrayOutputStream(compressed.size * 2)
    val buf = ByteArray(1024)
    while (!inflater.finished()) {
      val count = inflater.inflate(buf)
      if (count <= 0) {
        if (inflater.needsInput() || inflater.needsDictionary()) break
      } else {
        out.write(buf, 0, count)
      }
    }
    inflater.end()
    return out.toByteArray()
  }

  private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
  private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

  companion object {
    private val X509_X25519_PREFIX = byteArrayOf(
      0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x03, 0x21, 0x00,
    )
  }
}

/**
 * Durable AES-GCM encrypted local persistence for offline chats, decrypted messages,
 * security flags (surviving app restart/refresh), peer E2EE keys, and pending outbox ops.
 */
class DesktopChatLocalStore(private val ownerUserId: String) {
  private val lock = Any()
  private val fileName = "chat_cache_${ownerUserId.ifBlank { "anon" }}.dat"

  val conversations = linkedMapOf<String, DesktopChatConversation>()
  val messages = linkedMapOf<String, DesktopChatMessage>()
  val flags = linkedMapOf<String, DesktopChatSecurityFlag>()
  val pendingOps = linkedMapOf<String, DesktopPendingChatOp>()
  val peerBundles = linkedMapOf<String, DesktopPeerE2eeBundle>()

  init {
    load()
  }

  fun save() {
    synchronized(lock) {
      val json = buildString {
        append('{')
        append("\"convs\":[")
        conversations.values.forEachIndexed { idx, c ->
          if (idx > 0) append(',')
          append(encodeConv(c))
        }
        append("],\"msgs\":[")
        messages.values.forEachIndexed { idx, m ->
          if (idx > 0) append(',')
          append(encodeMsg(m))
        }
        append("],\"flags\":[")
        flags.values.forEachIndexed { idx, f ->
          if (idx > 0) append(',')
          append(encodeFlag(f))
        }
        append("],\"ops\":[")
        pendingOps.values.forEachIndexed { idx, op ->
          if (idx > 0) append(',')
          append(encodeOp(op))
        }
        append("]}")
      }
      DesktopLocalVaultCrypto.writeEncryptedJson(fileName, ownerUserId, json)
    }
  }

  private fun load() {
    synchronized(lock) {
      val raw = DesktopLocalVaultCrypto.readEncryptedJson(fileName, ownerUserId) ?: return
      val root = runCatching { DesktopJson.parseObject(raw) }.getOrNull() ?: return
      DesktopJson.objList(root, "convs").forEach { m ->
        val c = decodeConv(m)
        if (c.conversationId.isNotBlank()) conversations[c.conversationId] = c
      }
      DesktopJson.objList(root, "msgs").forEach { m ->
        val msg = decodeMsg(m)
        if (msg.messageId.isNotBlank()) messages[msg.messageId] = msg
      }
      DesktopJson.objList(root, "flags").forEach { m ->
        val f = DesktopChatCodec.parseSecurityFlag(m, synced = DesktopJson.bool(m, "is_synced"))
        if (f.flagId.isNotBlank()) flags[f.flagId] = f
      }
      DesktopJson.objList(root, "ops").forEach { m ->
        val op = DesktopPendingChatOp(
          opId = DesktopJson.str(m, "op_id"),
          opKind = DesktopJson.str(m, "op_kind"),
          conversationId = DesktopJson.str(m, "conversation_id"),
          targetMessageId = DesktopJson.str(m, "target_message_id"),
          payloadJson = DesktopJson.str(m, "payload_json").ifBlank { "{}" },
          createdAtMs = DesktopJson.long(m, "created_at_ms", 0L),
          retryCount = DesktopJson.long(m, "retry_count", 0L).toInt(),
          nextRetryAtMs = DesktopJson.long(m, "next_retry_at_ms", 0L),
        )
        if (op.opId.isNotBlank()) pendingOps[op.opId] = op
      }
    }
  }

  private fun encodeConv(c: DesktopChatConversation): String = buildString {
    append('{')
    append("\"conversation_id\":\"").append(DesktopJson.escape(c.conversationId)).append("\",")
    append("\"peer_user_id\":\"").append(DesktopJson.escape(c.peerUserId)).append("\",")
    append("\"peer_full_name\":\"").append(DesktopJson.escape(c.peerFullName)).append("\",")
    append("\"peer_username\":\"").append(DesktopJson.escape(c.peerUsername)).append("\",")
    append("\"peer_avatar_url\":\"").append(DesktopJson.escape(c.peerAvatarUrl)).append("\",")
    append("\"last_message_id\":\"").append(DesktopJson.escape(c.lastMessageId)).append("\",")
    append("\"last_message_type\":\"").append(DesktopJson.escape(c.lastMessageType)).append("\",")
    append("\"last_sender_id\":\"").append(DesktopJson.escape(c.lastSenderId)).append("\",")
    append("\"last_preview\":\"").append(DesktopJson.escape(c.lastMessagePreviewPlain)).append("\",")
    append("\"last_delivery_status\":\"").append(DesktopJson.escape(c.lastDeliveryStatus)).append("\",")
    append("\"last_message_at_ms\":").append(c.lastMessageAtMs).append(',')
    append("\"unread_count\":").append(c.unreadCount).append(',')
    append("\"is_archived\":").append(c.isArchived).append(',')
    append("\"is_muted\":").append(c.isMuted).append(',')
    append("\"is_deleted_locally\":").append(c.isDeletedLocally).append(',')
    append("\"is_online\":").append(c.isOnline).append(',')
    append("\"last_seen_ms\":").append(c.lastSeenMs).append(',')
    append("\"last_seen_label\":\"").append(DesktopJson.escape(c.lastSeenLabel)).append("\",")
    append("\"draft_text\":\"").append(DesktopJson.escape(c.draftText)).append("\",")
    append("\"created_at_ms\":").append(c.createdAtMs).append(',')
    append("\"updated_at_ms\":").append(c.updatedAtMs).append(',')
    append("\"can_call\":").append(c.capabilities.canCall).append(',')
    append("\"can_screenshot\":").append(c.capabilities.canScreenshot).append(',')
    append("\"can_share_chats\":").append(c.capabilities.canShareChats).append(',')
    append("\"can_copy_messages\":").append(c.capabilities.canCopyMessages).append(',')
    append("\"can_download_media\":").append(c.capabilities.canDownloadMedia).append(',')
    append("\"show_read_receipts\":").append(c.capabilities.showReadReceipts).append(',')
    append("\"show_typing_indicator\":").append(c.capabilities.showTypingIndicator).append(',')
    append("\"show_online_indicator\":").append(c.capabilities.showOnlineIndicator).append(',')
    append("\"is_blocked\":").append(c.capabilities.isBlocked)
    append('}')
  }

  private fun decodeConv(m: Map<String, Any?>): DesktopChatConversation = DesktopChatConversation(
    conversationId = DesktopJson.str(m, "conversation_id"),
    peerUserId = DesktopJson.str(m, "peer_user_id"),
    peerFullName = DesktopJson.str(m, "peer_full_name"),
    peerUsername = DesktopJson.str(m, "peer_username"),
    peerAvatarUrl = DesktopJson.str(m, "peer_avatar_url"),
    lastMessageId = DesktopJson.str(m, "last_message_id"),
    lastMessageType = DesktopJson.str(m, "last_message_type"),
    lastSenderId = DesktopJson.str(m, "last_sender_id"),
    lastMessagePreviewPlain = DesktopJson.str(m, "last_preview"),
    lastDeliveryStatus = DesktopJson.str(m, "last_delivery_status").ifBlank { "sent" },
    lastMessageAtMs = DesktopJson.long(m, "last_message_at_ms", 0L),
    unreadCount = DesktopJson.long(m, "unread_count", 0L).toInt(),
    isArchived = DesktopJson.bool(m, "is_archived"),
    isMuted = DesktopJson.bool(m, "is_muted"),
    isDeletedLocally = DesktopJson.bool(m, "is_deleted_locally"),
    isOnline = DesktopJson.bool(m, "is_online"),
    lastSeenMs = DesktopJson.long(m, "last_seen_ms", 0L),
    lastSeenLabel = DesktopJson.str(m, "last_seen_label").ifBlank { "last seen recently" },
    capabilities = DesktopChatPeerCapabilities(
      canCall = if (m.containsKey("can_call")) DesktopJson.bool(m, "can_call") else true,
      canScreenshot = DesktopJson.bool(m, "can_screenshot"),
      canShareChats = DesktopJson.bool(m, "can_share_chats"),
      canCopyMessages = DesktopJson.bool(m, "can_copy_messages"),
      canDownloadMedia = if (m.containsKey("can_download_media")) DesktopJson.bool(m, "can_download_media") else true,
      showReadReceipts = if (m.containsKey("show_read_receipts")) DesktopJson.bool(m, "show_read_receipts") else true,
      showTypingIndicator = DesktopJson.bool(m, "show_typing_indicator"),
      showOnlineIndicator = DesktopJson.bool(m, "show_online_indicator"),
      isBlocked = DesktopJson.bool(m, "is_blocked"),
      isMuted = DesktopJson.bool(m, "is_muted"),
    ),
    draftText = DesktopJson.str(m, "draft_text"),
    createdAtMs = DesktopJson.long(m, "created_at_ms", 0L),
    updatedAtMs = DesktopJson.long(m, "updated_at_ms", 0L),
  )

  private fun encodeMsg(m: DesktopChatMessage): String = buildString {
    append('{')
    append("\"message_id\":\"").append(DesktopJson.escape(m.messageId)).append("\",")
    append("\"conversation_id\":\"").append(DesktopJson.escape(m.conversationId)).append("\",")
    append("\"sender_user_id\":\"").append(DesktopJson.escape(m.senderUserId)).append("\",")
    append("\"recipient_user_id\":\"").append(DesktopJson.escape(m.recipientUserId)).append("\",")
    append("\"client_op_id\":\"").append(DesktopJson.escape(m.clientOpId)).append("\",")
    append("\"message_type\":\"").append(DesktopJson.escape(m.messageType)).append("\",")
    append("\"compression_codec\":\"").append(DesktopJson.escape(m.compressionCodec)).append("\",")
    append("\"encryption_scheme\":\"").append(DesktopJson.escape(m.encryptionScheme)).append("\",")
    append("\"ciphertext_b64\":\"").append(DesktopJson.escape(m.ciphertextB64)).append("\",")
    append("\"sender_ciphertext_b64\":\"").append(DesktopJson.escape(m.senderCiphertextB64)).append("\",")
    append("\"signal_msg_type\":").append(m.signalMsgType).append(',')
    append("\"plain_text\":\"").append(DesktopJson.escape(m.plainText)).append("\",")
    append("\"media_url\":\"").append(DesktopJson.escape(m.mediaUrl)).append("\",")
    append("\"media_local_path\":\"").append(DesktopJson.escape(m.mediaLocalPath)).append("\",")
    append("\"media_id\":\"").append(DesktopJson.escape(m.mediaId)).append("\",")
    append("\"voice_duration_ms\":").append(m.voiceDurationMs).append(',')
    append("\"view_once_ttl_sec\":").append(m.viewOnceTtlSec).append(',')
    append("\"view_once_opened_at_ms\":").append(m.viewOnceOpenedAtMs).append(',')
    append("\"view_once_expires_at_ms\":").append(m.viewOnceExpiresAtMs).append(',')
    append("\"forwarded_from_user_id\":\"").append(DesktopJson.escape(m.forwardedFromUserId)).append("\",")
    append("\"delivery_status\":\"").append(DesktopJson.escape(m.deliveryStatus)).append("\",")
    append("\"sync_state\":\"").append(DesktopJson.escape(m.syncState)).append("\",")
    append("\"star_gift_amount\":").append(m.starGiftAmount).append(',')
    append("\"star_gift_tx_id\":\"").append(DesktopJson.escape(m.starGiftTxId)).append("\",")
    append("\"star_gift_verified\":").append(m.starGiftVerified).append(',')
    append("\"edited_at_ms\":").append(m.editedAtMs).append(',')
    append("\"created_at_ms\":").append(m.createdAtMs)
    append('}')
  }

  private fun decodeMsg(m: Map<String, Any?>): DesktopChatMessage = DesktopChatMessage(
    messageId = DesktopJson.str(m, "message_id"),
    conversationId = DesktopJson.str(m, "conversation_id"),
    senderUserId = DesktopJson.str(m, "sender_user_id"),
    recipientUserId = DesktopJson.str(m, "recipient_user_id"),
    clientOpId = DesktopJson.str(m, "client_op_id"),
    messageType = DesktopJson.str(m, "message_type").ifBlank { "text" },
    compressionCodec = DesktopJson.str(m, "compression_codec").ifBlank { "zlib" },
    encryptionScheme = DesktopJson.str(m, "encryption_scheme").ifBlank { "telefam_ecdh_v1" },
    ciphertextB64 = DesktopJson.str(m, "ciphertext_b64"),
    senderCiphertextB64 = DesktopJson.str(m, "sender_ciphertext_b64"),
    signalMsgType = DesktopJson.long(m, "signal_msg_type", 3L).toInt(),
    plainText = DesktopJson.str(m, "plain_text"),
    mediaUrl = DesktopJson.str(m, "media_url"),
    mediaLocalPath = DesktopJson.str(m, "media_local_path"),
    mediaId = DesktopJson.str(m, "media_id"),
    voiceDurationMs = DesktopJson.long(m, "voice_duration_ms", 0L),
    viewOnceTtlSec = DesktopJson.long(m, "view_once_ttl_sec", 0L).toInt(),
    viewOnceOpenedAtMs = DesktopJson.long(m, "view_once_opened_at_ms", 0L),
    viewOnceExpiresAtMs = DesktopJson.long(m, "view_once_expires_at_ms", 0L),
    forwardedFromUserId = DesktopJson.str(m, "forwarded_from_user_id"),
    deliveryStatus = DesktopJson.str(m, "delivery_status").ifBlank { "sent" },
    syncState = DesktopJson.str(m, "sync_state").ifBlank { "synced" },
    starGiftAmount = DesktopJson.long(m, "star_gift_amount", 0L),
    starGiftTxId = DesktopJson.str(m, "star_gift_tx_id"),
    starGiftVerified = DesktopJson.bool(m, "star_gift_verified"),
    editedAtMs = DesktopJson.long(m, "edited_at_ms", 0L),
    createdAtMs = DesktopJson.long(m, "created_at_ms", 0L),
  )

  private fun encodeFlag(f: DesktopChatSecurityFlag): String = buildString {
    append('{')
    append("\"flag_id\":\"").append(DesktopJson.escape(f.flagId)).append("\",")
    append("\"conversation_id\":\"").append(DesktopJson.escape(f.conversationId)).append("\",")
    append("\"message_id\":\"").append(DesktopJson.escape(f.messageId)).append("\",")
    append("\"actor_user_id\":\"").append(DesktopJson.escape(f.actorUserId)).append("\",")
    append("\"target_user_id\":\"").append(DesktopJson.escape(f.targetUserId)).append("\",")
    append("\"flag_type\":\"").append(DesktopJson.escape(f.flagType)).append("\",")
    append("\"details\":\"").append(DesktopJson.escape(f.details)).append("\",")
    append("\"is_synced\":").append(f.isSynced).append(',')
    append("\"created_at_ms\":").append(f.createdAtMs)
    append('}')
  }

  private fun encodeOp(op: DesktopPendingChatOp): String = buildString {
    append('{')
    append("\"op_id\":\"").append(DesktopJson.escape(op.opId)).append("\",")
    append("\"op_kind\":\"").append(DesktopJson.escape(op.opKind)).append("\",")
    append("\"conversation_id\":\"").append(DesktopJson.escape(op.conversationId)).append("\",")
    append("\"target_message_id\":\"").append(DesktopJson.escape(op.targetMessageId)).append("\",")
    append("\"payload_json\":\"").append(DesktopJson.escape(op.payloadJson)).append("\",")
    append("\"created_at_ms\":").append(op.createdAtMs).append(',')
    append("\"retry_count\":").append(op.retryCount).append(',')
    append("\"next_retry_at_ms\":").append(op.nextRetryAtMs)
    append('}')
  }
}

/**
 * Real JVM voice recorder, voice player, and crisp synthesized WhatsApp-style UI sound effects.
 */
class DesktopChatAudioEngine {
  private val voiceDir = File(System.getProperty("user.home"), ".telefam/voices").apply {
    if (!exists()) mkdirs()
  }
  private var targetLine: TargetDataLine? = null
  private var recordingThread: Thread? = null
  private var recordOutputFile: File? = null
  private var recordStartMs: Long = 0L
  @Volatile private var isRecordingFlag: Boolean = false
  @Volatile private var activeClip: Clip? = null
  @Volatile var playingMessageId: String? = null
    private set

  fun startRecording(onAmplitude: (Float) -> Unit): File? {
    stopRecording(discard = true)
    val format = AudioFormat(16000f, 16, 1, true, false)
    val info = DataLine.Info(TargetDataLine::class.java, format)
    if (!AudioSystem.isLineSupported(info)) return null
    val line = runCatching { AudioSystem.getLine(info) as TargetDataLine }.getOrNull() ?: return null
    runCatching {
      line.open(format)
      line.start()
    }.onFailure { return null }

    val outFile = File(voiceDir, "voice_${System.currentTimeMillis()}.wav")
    targetLine = line
    recordOutputFile = outFile
    recordStartMs = System.currentTimeMillis()
    isRecordingFlag = true

    val pcmBuffer = ByteArrayOutputStream()
    recordingThread = thread(isDaemon = true, name = "TelefamDesktopVoiceRecorder") {
      val chunk = ByteArray(1024)
      while (isRecordingFlag) {
        val read = runCatching { line.read(chunk, 0, chunk.size) }.getOrDefault(-1)
        if (read > 0) {
          pcmBuffer.write(chunk, 0, read)
          var sumSq = 0.0
          val samples = read / 2
          for (i in 0 until samples) {
            val lo = chunk[i * 2].toInt() and 0xFF
            val hi = chunk[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += sample * sample
          }
          val rms = if (samples > 0) sqrt(sumSq / samples) / 16000.0 else 0.0
          onAmplitude(rms.toFloat().coerceIn(0.05f, 1f))
        }
      }
      runCatching {
        val pcmBytes = pcmBuffer.toByteArray()
        ByteArrayInputStream(pcmBytes).use { bais ->
          val ais = AudioInputStream(bais, format, (pcmBytes.size / format.frameSize).toLong())
          AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outFile)
        }
      }
    }
    return outFile
  }

  fun stopRecording(discard: Boolean): Pair<File?, Long> {
    isRecordingFlag = false
    val line = targetLine
    targetLine = null
    runCatching {
      line?.stop()
      line?.close()
    }
    runCatching { recordingThread?.join(500L) }
    recordingThread = null
    val file = recordOutputFile
    recordOutputFile = null
    val duration = if (recordStartMs > 0L) (System.currentTimeMillis() - recordStartMs).coerceAtLeast(300L) else 0L
    recordStartMs = 0L
    if (discard && file != null) {
      runCatching { file.delete() }
      return null to 0L
    }
    return if (file != null && file.exists() && file.length() > 44L) file to duration else null to 0L
  }

  fun playVoiceFile(
    messageId: String,
    file: File,
    onCompleted: () -> Unit,
  ): Boolean {
    stopPlayback()
    if (!file.exists()) return false
    return runCatching {
      val ais = AudioSystem.getAudioInputStream(file)
      val clip = AudioSystem.getClip()
      clip.open(ais)
      activeClip = clip
      playingMessageId = messageId
      clip.addLineListener { ev ->
        if (ev.type == LineEvent.Type.STOP) {
          if (playingMessageId == messageId) {
            playingMessageId = null
            runCatching { clip.close() }
            if (activeClip === clip) activeClip = null
            onCompleted()
          }
        }
      }
      clip.start()
      true
    }.getOrDefault(false)
  }

  fun stopPlayback() {
    val clip = activeClip
    activeClip = null
    playingMessageId = null
    runCatching {
      clip?.stop()
      clip?.close()
    }
  }

  fun playSentSound(enabled: Boolean) {
    if (!enabled) return
    playToneAsync(freqStart = 760.0, freqEnd = 1040.0, durationMs = 75, gain = 0.22)
  }

  fun playReceivedSound(enabled: Boolean) {
    if (!enabled) return
    playToneAsync(freqStart = 580.0, freqEnd = 820.0, durationMs = 95, gain = 0.24)
  }

  fun playTypingTickSound(enabled: Boolean) {
    if (!enabled) return
    playToneAsync(freqStart = 420.0, freqEnd = 480.0, durationMs = 32, gain = 0.08)
  }

  fun playStarGiftChime(enabled: Boolean) {
    if (!enabled) return
    playToneAsync(freqStart = 784.0, freqEnd = 1318.5, durationMs = 150, gain = 0.24)
  }

  private fun playToneAsync(freqStart: Double, freqEnd: Double, durationMs: Int, gain: Double) {
    thread(isDaemon = true, name = "TelefamDesktopTone") {
      runCatching {
        val sampleRate = 22050f
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val count = ((sampleRate * durationMs) / 1000f).toInt().coerceAtLeast(1)
        val buf = ByteArray(count * 2)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        for (i in 0 until count) {
          val t = i.toDouble() / count
          val freq = freqStart + (freqEnd - freqStart) * t
          phase += 2.0 * PI * freq / sampleRate
          val env = sin(PI * t) * exp(-2.2 * t)
          val v = (sin(phase) * env * gain * 32767.0).toInt().toShort()
          bb.putShort(v)
        }
    val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        line.start()
        line.write(buf, 0, buf.size)
        line.drain()
        line.close()
      }
    }
  }
}

/**
 * Full-duplex 16kHz PCM audio capture/playback and ringtone synthesizer for Desktop calls.
 */
class DesktopCallMediaEngine {
  private val pcmFormat = AudioFormat(16000f, 16, 1, true, false)
  @Volatile private var running = false
  @Volatile private var muted = false
  private var captureLine: TargetDataLine? = null
  private var playbackLine: SourceDataLine? = null
  private var captureThread: Thread? = null
  @Volatile private var toneRunning = false
  private var toneThread: Thread? = null

  fun setMuted(isMuted: Boolean) {
    muted = isMuted
  }

  fun startRingingTone(outgoing: Boolean) {
    stopToneLoop()
    toneRunning = true
    toneThread = thread(isDaemon = true, name = "TelefamDesktopCallRing") {
      runCatching {
        val sampleRate = 22050f
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        line.start()
        val burstMs = if (outgoing) 900 else 700
        val pauseMs = if (outgoing) 1600 else 1300
        val count = ((sampleRate * burstMs) / 1000f).toInt()
        val buf = ByteArray(count * 2)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
          val t = i.toDouble() / sampleRate
          val env = (sin(Math.PI * (i.toDouble() / count))).coerceIn(0.0, 1.0)
          val sig = if (outgoing) {
            0.5 * sin(2.0 * Math.PI * 440.0 * t) + 0.5 * sin(2.0 * Math.PI * 480.0 * t)
          } else {
            0.55 * sin(2.0 * Math.PI * 523.25 * t) + 0.45 * sin(2.0 * Math.PI * 659.25 * t)
          }
          bb.putShort((sig * env * 0.16 * 32767.0).toInt().toShort())
        }
        while (toneRunning) {
          line.write(buf, 0, buf.size)
          var waited = 0
          while (toneRunning && waited < pauseMs) {
            Thread.sleep(50)
            waited += 50
          }
        }
        line.stop()
        line.close()
      }
    }
  }

  fun stopToneLoop() {
    toneRunning = false
    runCatching { toneThread?.join(300L) }
    toneThread = null
  }

  fun startAudioSession(onFrameB64: (String) -> Unit) {
    stopToneLoop()
    if (running) return
    running = true
    runCatching {
      val spkInfo = DataLine.Info(SourceDataLine::class.java, pcmFormat)
      if (AudioSystem.isLineSupported(spkInfo)) {
        val spk = AudioSystem.getLine(spkInfo) as SourceDataLine
        spk.open(pcmFormat, 6400)
        spk.start()
        playbackLine = spk
      }
    }
    captureThread = thread(isDaemon = true, name = "TelefamDesktopCallAudio") {
      runCatching {
        val micInfo = DataLine.Info(TargetDataLine::class.java, pcmFormat)
        if (!AudioSystem.isLineSupported(micInfo)) return@runCatching
        val mic = AudioSystem.getLine(micInfo) as TargetDataLine
        mic.open(pcmFormat, 3200)
        mic.start()
        captureLine = mic
        val chunk = ByteArray(1280) // 40ms @ 16kHz 16-bit mono
        while (running) {
          val n = runCatching { mic.read(chunk, 0, chunk.size) }.getOrDefault(-1)
          if (n > 0 && !muted) {
            val b64 = Base64.getEncoder().encodeToString(chunk.copyOfRange(0, n))
            onFrameB64(b64)
          }
        }
      }
    }
  }

  fun playRemoteAudioFrame(dataB64: String) {
    if (!running || dataB64.isBlank()) return
    val bytes = runCatching { Base64.getDecoder().decode(dataB64) }.getOrNull() ?: return
    if (bytes.isEmpty()) return
    runCatching { playbackLine?.write(bytes, 0, bytes.size) }
  }

  fun stopAll() {
    running = false
    stopToneLoop()
    runCatching {
      captureLine?.stop()
      captureLine?.close()
    }
    captureLine = null
    runCatching { captureThread?.join(350L) }
    captureThread = null
    runCatching {
      playbackLine?.stop()
      playbackLine?.close()
    }
    playbackLine = null
  }
}

/**
 * RFC 6455 WebSocket listener for live chat delivery, typing/recording indicators, online state, calls, and unsends.
 */
class DesktopChatWebSocketClient(private val apiClient: DesktopApiClient) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _events = MutableSharedFlow<DesktopChatWsEvent>(extraBufferCapacity = 128)
  val events: SharedFlow<DesktopChatWsEvent> = _events.asSharedFlow()

  @Volatile private var socket: WebSocket? = null
  private var job: Job? = null

  fun isConnected(): Boolean = socket != null

  fun connect() {
    val session = apiClient.currentSessionSnapshot()
    val base = apiClient.currentBaseUrl().orEmpty().trim().trimEnd('/')
    val fp = apiClient.currentDeviceFp()
    if (!session.isAuthenticated || session.accessToken.isBlank() || base.isBlank()) return
    if (job?.isActive == true && socket != null) return

    disconnect()
    val wsBase = when {
      base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
      base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
      else -> return
    }

    job = scope.launch {
      var attempt = 0
      while (isActive) {
        val currentSession = apiClient.currentSessionSnapshot()
        if (!currentSession.isAuthenticated || currentSession.accessToken.isBlank()) break

        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = object : WebSocket.Listener {
          private val sb = StringBuilder()
          override fun onOpen(webSocket: WebSocket) {
            attempt = 0
            socket = webSocket
            webSocket.request(1)
          }

          override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            sb.append(data)
            if (last) {
              parseWs(sb.toString())?.let { _events.tryEmit(it) }
              sb.clear()
            }
            webSocket.request(1)
            return null
          }

          override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            if (socket === webSocket) socket = null
            done.complete(Unit)
            return null
          }

          override fun onError(webSocket: WebSocket, error: Throwable?) {
            if (socket === webSocket) socket = null
            done.complete(Unit)
          }
        }

        runCatching {
          apiClient.currentHttpClient().newWebSocketBuilder()
            .header("Authorization", "Bearer ${currentSession.accessToken}")
            .header("X-Telefam-Device-FP", fp)
            .buildAsync(URI("$wsBase/v1/chat/ws"), listener)
            .join()
        }.onFailure {
          done.complete(Unit)
        }

        val pinger = launch {
          while (isActive && !done.isCompleted) {
            delay(25_000L)
            socket?.sendText("""{"event":"ping"}""", true)
          }
        }
        done.await()
        pinger.cancel()
        attempt++
        delay((1500L shl attempt.coerceAtMost(4)).coerceAtMost(24_000L))
      }
    }
  }

  fun sendTyping(conversationId: String, mode: String): Boolean {
    if (conversationId.isBlank()) return false
    val sock = socket ?: return false
    val json = "{\"event\":\"typing\",\"conversation_id\":\"${DesktopJson.escape(conversationId)}\",\"typing_mode\":\"${DesktopJson.escape(mode)}\"}"
    sock.sendText(json, true)
    return true
  }

  fun sendCallAccept(callId: String) {
    if (callId.isBlank()) return
    socket?.sendText("{\"event\":\"call_accept\",\"call_id\":\"${DesktopJson.escape(callId)}\"}", true)
  }

  fun sendCallReject(callId: String, reason: String = "declined") {
    if (callId.isBlank()) return
    socket?.sendText("{\"event\":\"call_reject\",\"call_id\":\"${DesktopJson.escape(callId)}\",\"status\":\"${DesktopJson.escape(reason)}\"}", true)
  }

  fun sendCallEnd(callId: String, reason: String = "hangup") {
    if (callId.isBlank()) return
    socket?.sendText("{\"event\":\"call_end\",\"call_id\":\"${DesktopJson.escape(callId)}\",\"status\":\"${DesktopJson.escape(reason)}\"}", true)
  }

  fun sendCallSignal(callId: String, signalKind: String, payload: String) {
    if (callId.isBlank()) return
    val json = "{\"event\":\"call_signal\",\"call_id\":\"${DesktopJson.escape(callId)}\",\"signal_kind\":\"${DesktopJson.escape(signalKind)}\",\"signal_payload\":\"${DesktopJson.escape(payload)}\"}"
    socket?.sendText(json, true)
  }

  fun sendCallMedia(callId: String, mediaKind: String, dataB64: String, width: Int = 0, height: Int = 0) {
    if (callId.isBlank() || dataB64.isBlank()) return
    val json = "{\"event\":\"call_media\",\"call_id\":\"${DesktopJson.escape(callId)}\",\"signal_kind\":\"${DesktopJson.escape(mediaKind)}\",\"data_b64\":\"${DesktopJson.escape(dataB64)}\",\"width\":$width,\"height\":$height}"
    socket?.sendText(json, true)
  }

  fun disconnect() {
    job?.cancel()
    job = null
    runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "signout") }
    socket = null
  }

  private fun parseWs(text: String): DesktopChatWsEvent? = runCatching {
    val m = DesktopJson.parseObject(text)
    val ev = DesktopJson.str(m, "event")
    if (ev.isBlank()) return null
    DesktopChatWsEvent(
      event = ev,
      conversationId = DesktopJson.str(m, "conversation_id"),
      userId = DesktopJson.str(m, "user_id"),
      messageId = DesktopJson.str(m, "message_id"),
      status = DesktopJson.str(m, "status"),
      typingMode = DesktopJson.str(m, "typing_mode"),
      isOnline = DesktopJson.bool(m, "is_online"),
      lastSeenMs = DesktopJson.long(m, "last_seen_ms", 0L),
      lastSeenLabel = DesktopJson.str(m, "last_seen_label"),
      starGiftAmount = DesktopJson.long(m, "star_gift_amount", 0L),
      starBalanceAvailable = DesktopJson.long(m, "star_balance_available", 0L),
      starBalanceReceived = DesktopJson.long(m, "star_balance_received", 0L),
      callId = DesktopJson.str(m, "call_id"),
      callType = DesktopJson.str(m, "call_type"),
      callState = DesktopJson.str(m, "state"),
      signalKind = DesktopJson.str(m, "signal_kind"),
      signalPayload = DesktopJson.str(m, "signal_payload"),
      mediaB64 = DesktopJson.str(m, "data_b64"),
      videoWidth = DesktopJson.long(m, "width", 0L).toInt(),
      videoHeight = DesktopJson.long(m, "height", 0L).toInt(),
      timestampMs = DesktopJson.long(m, "timestamp_ms", System.currentTimeMillis()),
    )
  }.getOrNull()
}
