package kp.ops

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * An App's key and what it signs (contracts.md §4.4, Amendment 33). GitHub hands an App's key out as PKCS#1 — `BEGIN
 * RSA PRIVATE KEY` — and the JDK reads PKCS#8: the key is wrapped in a PKCS#8 envelope in memory. Nothing of a key is
 * ever put into a message: a key that cannot be read is [NotAKey], which says why and quotes nothing.
 */
internal object Keys {
    class NotAKey(message: String) : Exception(message, null, false, false)

    private val PKCS1 = Regex("-----BEGIN RSA PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END RSA PRIVATE KEY-----")
    private val PKCS8 = Regex("-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----")

    /** The RSA private key in [pem]: PKCS#1, as GitHub gives it, or PKCS#8. */
    fun privateKey(pem: String): PrivateKey {
        val pkcs8 = PKCS1.find(pem)?.let { wrap(base64(it.groupValues[1])) }
            ?: PKCS8.find(pem)?.let { base64(it.groupValues[1]) }
            ?: throw NotAKey("is no PEM RSA private key (BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY)")
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (_: GeneralSecurityException) {
            throw NotAKey("cannot be read as an RSA private key")
        }
    }

    /** `<header>.<payload>.<signature>`, each base64url without padding, signed RS256 with [key]. */
    fun signed(header: String, payload: String, key: PrivateKey): String {
        val unsigned = "${url(header.toByteArray())}.${url(payload.toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(unsigned.toByteArray())
        }.sign()
        return "$unsigned.${url(signature)}"
    }

    private fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64(text: String): ByteArray = try {
        Base64.getMimeDecoder().decode(text.filterNot(Char::isWhitespace))
    } catch (_: IllegalArgumentException) {
        throw NotAKey("is not base64 between its PEM lines")
    }

    /**
     * PKCS#1's `RSAPrivateKey` in PKCS#8's `PrivateKeyInfo`: SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL },
     * OCTET STRING { the PKCS#1 key } } (RFC 5208, RFC 8017 A.1).
     */
    private fun wrap(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val rsaEncryption = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        return der(0x30, version + rsaEncryption + der(0x04, pkcs1))
    }

    private fun der(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    /** A DER length: short form below 128, else the long form's byte count and the bytes, big-endian. */
    private fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = generateSequence(n) { it ushr 8 }.takeWhile { it > 0 }.map { (it and 0xff).toByte() }.toList().reversed().toByteArray()
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
    }
}
