package com.syncliko.android.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.syncliko.transport.DeviceIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The phone's TLS identity: an EC P-256 key that never leaves Android Keystore, with the
 * self-signed certificate Keystore issues for it. The Mac pins SHA-256 of that certificate.
 */
object KeystoreIdentity {
    private const val ALIAS = "syncliko-identity"
    private const val PROVIDER = "AndroidKeyStore"

    fun loadOrCreate(deviceName: String): DeviceIdentity {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generate(deviceName)
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val cert = keyStore.getCertificate(ALIAS) as X509Certificate
        return object : DeviceIdentity {
            override val privateKey = key
            override val certificate = cert
        }
    }

    fun delete() {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun generate(deviceName: String) {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS signs with SHA-256; Conscrypt may also request raw (NONE) signing of a precomputed digest.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=Syncliko ${deviceName.replace(",", " ")}, O=Syncliko"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 86_400_000L * 365 * 20))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }
}
