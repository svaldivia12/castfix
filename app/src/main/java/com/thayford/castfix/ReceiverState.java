package com.thayford.castfix;

/**
 * Whether the Cast receiver holds its device credentials.
 *
 * <p>Decided by making the same device-auth handshake a phone makes, not by reading the log.
 *
 * <p>The log was tried first and is not usable for this. On Google TV Streamer (kirkwood) the main ring
 * buffer is 256 KiB and the receiver alone fills it well under a day, so once it rolls the single line that
 * recorded the load is gone while the credentials stay in memory. The receiver also logs nothing at all when
 * it is started with credentials already in hand. And at the one moment the answer matters - just after the
 * setup wizard is enabled - a retry that failed a second earlier is still in the buffer, and because both
 * timestamps truncate to the same epoch second it is indistinguishable from a fresh failure. Each of those
 * produced a wrong verdict on hardware before this check replaced them.
 *
 * <p>The handshake answers the actual question in under a second, and is read-only: one TLS connection to the
 * receiver's own port on loopback, one authentication challenge, and a look at whether the reply is signed.
 */
final class ReceiverState {

    /** The Cast device-auth port, the same one phones use. */
    static final int CAST_PORT = 8009;
    /** A local loopback handshake answers in well under a second; this only bounds a hung receiver. */
    static final int PROBE_TIMEOUT_MS = 3_000;

    /** Log messages used for diagnosis only. They never decide an outcome. */
    static final String CREDS_LOADED = "Created singleton instance of ClientAuthSigner";
    static final String CREDS_MISSING = "Cannot get device credentials";
    static final String CREDS_LOADING = "Loading factory credentials";

    private ReceiverState() {
    }

    enum Probe {
        /** The receiver answered with a signature: credentials are in memory. */
        SIGNED,
        /** The receiver answered with an empty signature: it has no credentials. */
        UNSIGNED,
        /** No answer: the port is closed or the receiver is not serving. */
        UNREACHABLE
    }

    /**
     * Asks the receiver whether it can sign.
     *
     * <p>Read-only, and confined to 127.0.0.1 on the TV: this stays within the app's stated rule of using no
     * network beyond adb over loopback and the local mDNS lookup.
     *
     * @param log receives the failure cause, when there is one and a log was supplied
     */
    static Probe probe(Shell shell, FixLog log) {
        return probeLocal(CAST_PORT, PROBE_TIMEOUT_MS, log);
    }

    /** The same check with no adb and no logging, for a caller that only needs the verdict. */
    static Probe probe(int port, int timeoutMs) {
        return probeLocal(port, timeoutMs, null);
    }

    /**
     * The handshake itself. Kept separate from {@link #probe(Shell, FixLog)} so it can be reasoned about
     * without a device, and so a failure to reach the port is reported as such rather than as "no credentials".
     */
    static Probe probeLocal(int port, int timeoutMs, FixLog log) {
        java.net.Socket socket = null;
        try {
            socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) sslContext().getSocketFactory()
                    .createSocket(socket, "127.0.0.1", port, true);
            tls.setUseClientMode(true);
            tls.startHandshake();
            java.io.OutputStream out = tls.getOutputStream();
            byte[] nonce = new byte[16];
            new java.security.SecureRandom().nextBytes(nonce);
            byte[] challenge = concat(
                    fieldVarint(1, 1),                                  // signature_algorithm: RSASSA_PKCS1_V1_5
                    fieldBytes(2, nonce));                              // sender_nonce
            byte[] deviceAuth = fieldBytes(1, challenge);               // DeviceAuthMessage.challenge
            out.write(castMessage("urn:x-cast:com.google.cast.tp.deviceauth", deviceAuth, true));
            out.flush();
            byte[] reply = readFrame(tls.getInputStream());
            if (reply == null) {
                if (log != null) log.add("probe: the receiver closed before answering");
                return Probe.UNREACHABLE;
            }
            return signatureIsEmpty(reply) ? Probe.UNSIGNED : Probe.SIGNED;
        } catch (Exception e) {
            // Name the cause: "unreachable" alone cannot tell a refused port from an unusable TLS setup.
            if (log != null) log.add("probe failed: " + e);
            return Probe.UNREACHABLE;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // closing is best effort
                }
            }
        }
    }

    /**
     * One line describing what the log currently says, for the run log only.
     *
     * <p>Deliberately not used to decide anything: measured on a kirkwood boot, the retry that failed 0.9 s
     * before the wizard was enabled and a genuine fresh failure are the same epoch second once truncated, so
     * this text can be misleading in exactly the case that matters.
     */
    static String describe(Shell shell) {
        try {
            String line = shell.run("logcat -d -v epoch -e \"" + CREDS_LOADED + "|" + CREDS_MISSING + "\"");
            long now = secondsNow(shell);
            long loaded = 0;
            long failure = 0;
            for (String entry : line.split("\n")) {
                long at = epochOf(entry);
                if (at <= 0) continue;
                if (entry.contains(CREDS_LOADED) && at > loaded) loaded = at;
                if (entry.contains(CREDS_MISSING) && at > failure) failure = at;
            }
            if (loaded > failure) return "log says credentials loaded";
            if (failure > 0) return "log says last failure " + (now - failure) + " s ago";
            return "log says nothing about credentials";
        } catch (Exception e) {
            return "log unreadable: " + e;
        }
    }

    /** Wall clock on the device, in epoch seconds, or 0 if it can't be read. */
    static long secondsNow(Shell shell) {
        try {
            return parseLong(shell.run("date +%s"));
        } catch (Exception e) {
            return 0;
        }
    }

    static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The leading epoch field of a {@code -v epoch} line, truncated to whole seconds, or 0. */
    static long epochOf(String line) {
        String value = line.trim();
        int end = 0;
        while (end < value.length() && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) return 0;
        String number = value.substring(0, end);
        // A "10-31 05:49:34.000" line (the default format) starts with digits too. An epoch token is never
        // followed by '-', and logcat prints it with a fraction.
        if (end < value.length() && value.charAt(end) == '-') return 0;
        if (!number.contains(".")) return 0;
        number = number.substring(0, number.indexOf('.'));
        // A real epoch is 9+ digits in this century; anything shorter is a date fragment or a stray number.
        if (number.length() < 9) return 0;
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A context that can talk to the receiver's own port. The chain it presents is signed by Google's Cast CA,
     * which is not in the platform trust store, so the handshake has to accept it: this is a local signing
     * check against the TV's own loopback port, not a channel used to trust anything, and the reply is only
     * inspected for whether a signature is present.
     */
    private static javax.net.ssl.SSLContext sslContext() throws Exception {
        javax.net.ssl.TrustManager[] trustAll = {new javax.net.ssl.X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                // not used: this app is the client
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                // deliberately accepted; see the comment on sslContext()
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        }};
        javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(null, trustAll, new java.security.SecureRandom());
        return context;
    }

    /** A Cast v2 message: 4-byte big-endian length, then the protobuf body. */
    private static byte[] castMessage(String namespace, byte[] payload, boolean binary) throws Exception {
        byte[] body = concat(
                fieldVarint(1, 0),                       // protocolVersion = CASTV2_1_0
                fieldBytes(2, "sender-0".getBytes("UTF-8")),
                fieldBytes(3, "receiver-0".getBytes("UTF-8")),
                fieldBytes(4, namespace.getBytes("UTF-8")),
                fieldVarint(5, binary ? 1 : 0),
                fieldBytes(binary ? 7 : 6, payload));
        java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer length = java.nio.ByteBuffer.allocate(4).putInt(body.length);
        framed.write(length.array());
        framed.write(body);
        return framed.toByteArray();
    }

    private static byte[] readFrame(java.io.InputStream in) throws Exception {
        byte[] header = readFully(in, 4);
        if (header == null) return null;
        int length = java.nio.ByteBuffer.wrap(header).getInt();
        if (length <= 0 || length > 1 << 20) return null;
        return readFully(in, length);
    }

    private static byte[] readFully(java.io.InputStream in, int count) throws Exception {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(buffer, read, count - read);
            if (n < 0) return null;
            read += n;
        }
        return buffer;
    }

    /**
     * True when the DeviceAuthMessage came back with a zero-length signature, i.e. the receiver has no private
     * key to sign with. Walks the outer CastMessage, its binary payload, the DeviceAuthMessage and its
     * response, and looks at response.signature. A parse it cannot follow counts as empty: claiming signed
     * when the reply could not be read would hide exactly the failure this exists to catch.
     */
    static boolean signatureIsEmpty(byte[] message) {
        byte[] payload = findBytes(message, 7);
        if (payload == null) return true;
        byte[] response = findBytes(payload, 2);       // DeviceAuthMessage.response
        if (response == null) return true;
        byte[] signature = findBytes(response, 1);     // DeviceAuthResponse.signature
        return signature == null || signature.length == 0;
    }

    /** The value of the first length-delimited field with this number, or null. */
    private static byte[] findBytes(byte[] buffer, int field) {
        int i = 0;
        while (i < buffer.length) {
            long key = 0;
            int shift = 0;
            while (true) {
                if (i >= buffer.length) return null;
                int b = buffer[i++] & 0xFF;
                key |= (long) (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) break;
                if (shift > 63) return null;
            }
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 2) {
                long length = 0;
                shift = 0;
                while (true) {
                    if (i >= buffer.length) return null;
                    int b = buffer[i++] & 0xFF;
                    length |= (long) (b & 0x7F) << shift;
                    shift += 7;
                    if ((b & 0x80) == 0) break;
                    if (shift > 63) return null;
                }
                if (length < 0 || i + length > buffer.length) return null;
                if (number == field) {
                    byte[] value = new byte[(int) length];
                    System.arraycopy(buffer, i, value, 0, (int) length);
                    return value;
                }
                i += (int) length;
            } else if (wire == 0) {
                while (true) {
                    if (i >= buffer.length) return null;
                    if ((buffer[i++] & 0x80) == 0) break;
                }
            } else if (wire == 5) {
                i += 4;
            } else if (wire == 1) {
                i += 8;
            } else {
                return null;
            }
        }
        return null;
    }

    private static byte[] fieldVarint(int field, long value) {
        return concat(varint((field << 3) | 0), varint(value));
    }

    private static byte[] fieldBytes(int field, byte[] value) {
        return concat(varint((field << 3) | 2), varint(value.length), value);
    }

    private static byte[] varint(long value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (true) {
            long b = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                out.write((int) (b | 0x80));
            } else {
                out.write((int) b);
                return out.toByteArray();
            }
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
