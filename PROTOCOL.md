# Syncliko Protocol v1

Wire protocol between the macOS app (server) and the Android app (client).
Both implementations MUST pass the shared test vectors in [`protocol/vectors.json`](protocol/vectors.json).

## 1. Transport

- TCP + **TLS 1.3**, mutual authentication. Each side presents a long-lived **self-signed ECDSA P-256** certificate.
- Trust is **pinned**: a peer is accepted only if `SHA-256(DER of leaf certificate)` matches a fingerprint learned during pairing. Normal CA/hostname validation is NOT used.
- The macOS app listens on TCP port **47823** (falls back to an ephemeral port if busy) and advertises Bonjour service `_syncliko._tcp` with TXT records:
  - `v=1` — protocol version
  - `id=<macDeviceId>`
  - `fp=<base64url fingerprint, first 16 chars>` — lets clients pick the right Mac without connecting
- ALPN: `syncliko/1`.
- One TLS connection per paired device. The client reconnects with exponential backoff (0.5 s → 30 s, ±20% jitter).

## 2. Framing

Every message is one frame. All integers are big-endian.

```
+--------+----------------+-----------------+-------------------+-----------------+
| type   | headerLength   | payloadLength   | header            | payload         |
| u8     | u32            | u32             | UTF-8 JSON object | raw bytes       |
+--------+----------------+-----------------+-------------------+-----------------+
```

- Fixed prefix is 9 bytes.
- `headerLength` ≤ 65 536. `payloadLength` ≤ 1 048 576. A larger value is a protocol error and closes the connection.
- The header is a JSON object. Encoders SHOULD emit sorted keys without whitespace. Decoders MUST ignore unknown keys and MUST reject unknown frame types with an `error` frame (and keep the connection open).

## 3. Messages

| Type | Name | Direction | Header | Payload |
|---|---|---|---|---|
| `0x01` | `hello` | both, first frame | `protocolVersion`, `deviceId`, `deviceName`, `platform` (`macos`\|`android`), `appVersion` | — |
| `0x02` | `pairRequest` | client → server | `token`, `deviceId`, `deviceName`, `platform` | — |
| `0x03` | `pairResult` | server → client | `accepted` (bool), `deviceId`, `deviceName`, `reason?` | — |
| `0x10` | `clip` | both | see §4 | first chunk of content |
| `0x11` | `clipChunk` | both | `id`, `offset` | next chunk |
| `0x12` | `ack` | both | `id` | — |
| `0x20` | `ping` | both | `t` (ms since epoch) | — |
| `0x21` | `pong` | both | `t` (echoed) | — |
| `0x30` | `requestLatest` | both | — | — |
| `0x31` | `endpoints` | server → client | `endpoints`: `[{host, port, kind}]`, kind ∈ `lan`\|`tailscale`\|`bonjour` | — |
| `0x7F` | `error` | both | `code`, `message` | — |

### Session flow

1. TLS handshake (mutual, pinned — see §5 for the pairing exception).
2. Both sides send `hello`. A `protocolVersion` mismatch → `error{code:"version"}` and close.
3. The server sends `endpoints`. Both sides MAY send `requestLatest`.
4. Steady state: `clip`/`clipChunk`/`ack`, `ping` every 45 s. A side that hears nothing for 120 s closes the connection.

## 4. Clips

`clip` header fields:

| Field | Type | Notes |
|---|---|---|
| `id` | string (UUID) | unique per clip |
| `origin` | string | `deviceId` of the device where the copy happened |
| `createdAt` | int | ms since epoch, origin clock |
| `kind` | `text` \| `url` \| `image` | |
| `mime` | string | `text/plain;charset=utf-8`, `text/uri-list`, `image/png`, `image/jpeg`, `image/heic` |
| `size` | int | total content bytes |
| `sha256` | string | lowercase hex of the full content |
| `sensitive` | bool | origin marked it as a password/secret. The receiver marks its clipboard the same way (`EXTRA_IS_SENSITIVE`, `org.nspasteboard.ConcealedType`) |

- Content is UTF-8 for `text`/`url` and the encoded image bytes for `image`.
- Content larger than **262 144 bytes** (256 KiB) is split: the `clip` frame carries bytes `[0, 256KiB)`, then `clipChunk` frames with increasing `offset` follow until `size` is reached. Frames of other types MAY be interleaved.
- The receiver verifies `sha256` once all bytes arrive, writes the content to the local clipboard, and sends `ack{id}`.
- Maximum `size` is **20 MiB**. Larger content is not sent.

### Echo suppression

- Each side keeps the `sha256` of the last clip it **received**. When the local clipboard changes and the new content's hash equals it, the change is not sent.
- macOS additionally tags pasteboard items it writes with type `com.syncliko.origin`. Android adds the extra `com.syncliko.origin` to `ClipDescription`.

### Latest-wins and `requestLatest`

The receiver of `requestLatest` answers with its most recent local clip if it is younger than **120 s**. Otherwise it does not answer. A clip whose `createdAt` is older than the receiver's current clipboard is still applied (clocks are not trusted); ordering is arrival order.

## 5. Pairing

1. The Mac opens a **pairing window** (5 min) and shows a QR code with the URL:
   ```
   syncliko://pair?v=1&id=<macDeviceId>&n=<url-encoded name>&fp=<base64url sha256>&t=<base64url 16-byte token>&p=<port>&h=<host1>,<host2>,...
   ```
   `h` lists candidate hosts: LAN IPv4 addresses, Tailscale 100.x addresses, Tailscale MagicDNS name.
2. The client connects to any host, pins the server certificate against `fp`, and presents its own certificate.
3. During the pairing window the server accepts **unknown** client certificates on a *provisional* connection. The only allowed first frame there is `pairRequest`. Anything else → close.
4. The server checks `token` (constant-time compare, single use), asks the user to confirm on the Mac, stores the client's certificate fingerprint, and replies with `pairResult{accepted:true}`. Then the session continues normally from the `hello` step.
5. Base64url is used without padding (RFC 4648 §5).

## 6. Error codes

`version`, `unauthorized`, `badFrame`, `unknownType`, `tooLarge`, `hashMismatch`, `pairingClosed`, `badToken`, `rejected`.
