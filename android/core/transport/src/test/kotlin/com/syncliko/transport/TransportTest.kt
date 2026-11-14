package com.syncliko.transport

import com.syncliko.protocol.Clip
import com.syncliko.protocol.ClipKind
import com.syncliko.protocol.Endpoint
import com.syncliko.protocol.Endpoints
import com.syncliko.protocol.Frame
import com.syncliko.protocol.Hello
import com.syncliko.protocol.Message
import com.syncliko.protocol.PairingInvite
import com.syncliko.protocol.Platform
import com.syncliko.protocol.SynclikoProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assume.assumeTrue
import java.io.DataInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Self-signed P-256 identity, like the one Android Keystore generates on the phone. */
fun testIdentity(cn: String): DeviceIdentity {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val name = X500Name("CN=$cn")
    val now = System.currentTimeMillis()
    val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 86_400_000L * 365), name, pair.public)
        .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
    return Tls.identityOf(pair.private, JcaX509CertificateConverter().getCertificate(holder))
}

/** Minimal JVM stand-in for the Mac server: hello exchange, endpoints, then echoes one clip back. */
class FakeMacServer(val identity: DeviceIdentity, trustedClient: ByteArray) {
    val server: SSLServerSocket = (Tls.context(identity) { it.contentEquals(trustedClient) }.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
        needClientAuth = true
        enabledProtocols = arrayOf("TLSv1.3")
    }
    val port get() = server.localPort
    val received = AtomicReference<Clip?>()

    init {
        thread(isDaemon = true) {
            runCatching {
                val socket = server.accept() as SSLSocket
                val input = DataInputStream(socket.inputStream)
                val out = socket.outputStream
                fun send(m: Message) = out.write(SynclikoProtocol.encode(m).encode()).also { out.flush() }
                val hello = SynclikoProtocol.decode(Frame.read(input))
                check(hello is Message.HelloMsg)
                send(Message.HelloMsg(Hello("test", "mac-1", "Fake Mac", Platform.MACOS)))
                send(Message.EndpointsMsg(Endpoints(listOf(Endpoint(host = "127.0.0.1", kind = Endpoint.Kind.LAN, port = port)))))
                while (true) {
                    when (val m = SynclikoProtocol.decode(Frame.read(input))) {
                        is Message.ClipMsg -> {
                            received.set(Clip(m.header, m.data))
                            // Reply with a large chunked image to exercise reassembly.
                            val image = Clip.make(ClipKind.IMAGE, "image/png", ByteArray(600_000) { (it % 249).toByte() }, "mac-1")
                            image.messages().forEach(::send)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

class TransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phone = testIdentity("Phone")
    private val hello = Hello("test", "android-1", "Test Phone", Platform.ANDROID)

    @AfterTest fun tearDown() = scope.cancel()

    @Test
    fun connectsSyncsAndReassembles() = runBlocking {
        val mac = testIdentity("Mac")
        val server = FakeMacServer(mac, phone.fingerprint)
        val client = SyncClient(phone, hello, scope)
        // A dead address first: the race must still pick the live one.
        client.start(MacTarget("mac-1", "Fake Mac", mac.fingerprint, listOf(HostPort("10.255.255.1", 9, Endpoint.Kind.LAN), HostPort("127.0.0.1", server.port, Endpoint.Kind.LAN))))
        val connected = withTimeout(10_000) { client.state.first { it is SyncClient.State.Connected } }
        assertIs<SyncClient.State.Connected>(connected)
        assertEquals("Fake Mac", connected.peer.deviceName)
        assertEquals(server.port, withTimeout(5_000) { client.endpoints.first() }.single().port)

        val text = Clip.text("Привет с телефона", "android-1")
        assertTrue(client.send(text))
        val image = withTimeout(5_000) { client.clips.first() }
        assertEquals(text, server.received.get())
        assertEquals(ClipKind.IMAGE, image.kind)
        assertEquals(600_000, image.content.size)
        client.stop()
    }

    @Test
    fun refusesWrongServerPin() = runBlocking {
        val mac = testIdentity("Mac")
        val server = FakeMacServer(mac, phone.fingerprint)
        val client = SyncClient(phone, hello, scope)
        client.start(MacTarget("mac-1", "Fake Mac", testIdentity("Impostor").fingerprint, listOf(HostPort("127.0.0.1", server.port, Endpoint.Kind.LAN))))
        val waiting = withTimeout(10_000) { client.state.first { it is SyncClient.State.Waiting } }
        assertIs<SyncClient.State.Waiting>(waiting)
        assertTrue(waiting.error!!.contains("identity changed"), waiting.error)
        client.stop()
    }

    /**
     * Interop with the real Swift server. Run `swift run syncliko-probe serve --port 47999` in
     * mac/Packages/SynclikoCore, then: SYNCLIKO_INVITE='<url it prints>' SYNCLIKO_HOST=127.0.0.1 ./gradlew :core:transport:test
     */
    @Test
    fun interopWithSwiftServer() = runBlocking {
        val url = System.getProperty("syncliko.invite")
        assumeTrue("set SYNCLIKO_INVITE to run", url != null)
        val invite = PairingInvite.parse(url!!)!!
        val host = System.getProperty("syncliko.host")
        val effective = if (host != null) invite.copy(hosts = listOf(host)) else invite
        val outcome = SyncClient.pair(phone, hello, effective, scope)
        assertIs<PairOutcome.Paired>(outcome, (outcome as? PairOutcome.Failed)?.reason)
        println("paired with ${outcome.target.name}")

        val client = SyncClient(phone, hello, scope)
        client.start(outcome.target)
        val connected = withTimeout(10_000) { client.state.first { it is SyncClient.State.Connected } }
        println("connected: $connected")
        val endpoints = withTimeout(5_000) { client.endpoints.first() }
        println("endpoints from Mac: $endpoints")
        assertTrue(client.send(Clip.text("Hello from Kotlin 🤖 ${System.currentTimeMillis()}", "android-1")))
        assertTrue(client.send(Clip.make(ClipKind.IMAGE, "image/png", ByteArray(700_000) { (it % 247).toByte() }, "android-1")))
        Thread.sleep(1_000)
        client.stop()
    }
}
