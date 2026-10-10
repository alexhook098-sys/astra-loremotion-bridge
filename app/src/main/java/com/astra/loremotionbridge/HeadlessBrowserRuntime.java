package com.astra.loremotionbridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the persistent Linux headless Chrome process used by ASTRA.
 * Chrome is downloaded by Android's trusted HTTPS stack, so the Linux
 * rootfs does not need curl or unzip merely to obtain the browser archive.
 */
public final class HeadlessBrowserRuntime {
    private static final Object LOCK = new Object();
    private static final String CHROME_VERSION = "155.0.8059.39";
    private static final String CHROME_URL =
            "https://storage.googleapis.com/chrome-for-testing-public/" +
            CHROME_VERSION + "/linux-arm64/chrome-headless-shell-linux-arm64.zip";
    private static final String CHROME_DIR = "chrome-headless-shell-linux-arm64";
    private static final String CHROME_EXECUTABLE =
            "/opt/chrome/chrome-headless-shell-linux-arm64/chrome-headless-shell";
    private static final int CDP_PORT = 9222;
    private static final long MIN_FREE_BYTES = 900L * 1024L * 1024L;
    private static volatile String state = "STOPPED";
    private static volatile String detail = "Нажми «Запустить ASTRA Browser Runtime».";
    private static volatile Context appContext;
    private static volatile Process chromeProcess;

    private HeadlessBrowserRuntime() {}

    public static void startAsync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            appContext = app;
            if (isCdpOnline()) {
                state = "ONLINE";
                detail = "Headless Chrome is online; the same profile is retained between starts.";
                appendLog("CDP already online at 127.0.0.1:" + CDP_PORT);
                return;
            }
            if ("STARTING".equals(state) || "DOWNLOADING_CHROME".equals(state)
                    || "EXTRACTING_CHROME".equals(state) || "INSTALLING_DEPENDENCIES".equals(state)
                    || "LAUNCHING_CHROME".equals(state) || "WAITING_FOR_CDP".equals(state)) {
                return;
            }
            state = "STARTING";
            detail = "Preparing Ubuntu rootfs and persistent Chrome profile.";
            Thread worker = new Thread(() -> initialize(app), "ASTRA-HeadlessChrome-Init");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void initialize(Context context) {
        appContext = context.getApplicationContext();
        appendLog("\n=== ASTRA headless runtime start ===");
        try {
            File rootDir = ProrootRuntime.rootDir(context);
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                throw new IOException("Cannot create private runtime directory: " + rootDir);
            }
            if (rootDir.getUsableSpace() < MIN_FREE_BYTES) {
                throw new IOException("Недостаточно свободного места. Освободи минимум 900 МБ и повтори запуск.");
            }

            detail = "Preparing Proroot and Ubuntu rootfs.";
            appendLog("Preparing Proroot libraries...");
            ProrootRuntime.prepare(context);
            ProrootRuntime.extractRootfs(context);
            File rootfs = ProrootRuntime.rootfsDir(context);
            prepareRootfsDns(context, rootfs);

            File executable = new File(rootfs, CHROME_EXECUTABLE.substring(1));
            if (!executable.isFile() || executable.length() < 10_000_000L) {
                state = "DOWNLOADING_CHROME";
                detail = "Downloading official ARM64 Chrome Headless Shell (first run only).";
                downloadChrome(rootDir);
                state = "EXTRACTING_CHROME";
                detail = "Extracting Chrome into the persistent Ubuntu rootfs.";
                extractChrome(rootDir, rootfs);
            } else {
                appendLog("Chrome archive already extracted: " + executable.length() + " bytes");
            }

            File depsMarker = new File(rootfs, "opt/chrome/.astra_deps_ready");
            if (!depsMarker.isFile()) {
                state = "INSTALLING_DEPENDENCIES";
                detail = "Installing only Chrome runtime libraries inside Ubuntu; full logs are available at /headless/log.";
                installRuntimeDependencies(context);
            } else {
                appendLog("Runtime dependency marker exists; skipping apt setup.");
            }

            if (isCdpOnline()) {
                state = "ONLINE";
                detail = "Headless Chrome is online at 127.0.0.1:9222. Profile persists across restarts.";
                return;
            }

            state = "LAUNCHING_CHROME";
            detail = "Starting headless Chrome with persistent LoreMotion profile.";
            launchChrome(context);
            state = "WAITING_FOR_CDP";
            detail = "Waiting for Chrome DevTools Protocol to become ready.";
            for (int i = 0; i < 60; i++) {
                if (isCdpOnline()) {
                    state = "ONLINE";
                    detail = "Headless Chrome online. CDP: http://127.0.0.1:9222/json/version. Sign in once in this separate profile; it will be retained.";
                    appendLog("CDP ready at http://127.0.0.1:9222/json/version");
                    return;
                }
                Process running = chromeProcess;
                if (running != null && !running.isAlive()) {
                    throw new IOException("Chrome exited before CDP became ready (exit=" + running.exitValue() + "). See /headless/log.");
                }
                Thread.sleep(1000L);
            }
            throw new IOException("Chrome did not open CDP port 9222 within 60 seconds. See /headless/log.");
        } catch (Exception e) {
            state = "FAILED";
            detail = e.getClass().getSimpleName() + ": " + safe(e.getMessage());
            appendLog("RUNTIME FAILED: " + detail);
        }
    }

    private static void downloadChrome(File rootDir) throws IOException {
        File archive = new File(rootDir, "chrome-headless-shell-" + CHROME_VERSION + ".zip");
        File partial = new File(rootDir, archive.getName() + ".part");
        if (partial.exists() && !partial.delete()) throw new IOException("Cannot remove incomplete Chrome download.");
        HttpURLConnection connection = null;
        try {
            appendLog("Downloading official Chrome Headless Shell from Google Storage: " + CHROME_URL);
            connection = (HttpURLConnection) new URL(CHROME_URL).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "ASTRA-BrowserRuntime/1.0");
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Chrome download HTTP " + response);
            }
            long expected = connection.getContentLengthLong();
            if (expected > 0 && rootDir.getUsableSpace() < expected + MIN_FREE_BYTES) {
                throw new IOException("Недостаточно места для загрузки и распаковки Chrome. Освободи место и повтори запуск.");
            }
            long copied = 0;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    copied += count;
                }
                out.getFD().sync();
            }
            if (expected > 0 && copied != expected) {
                throw new IOException("Incomplete Chrome download: expected " + expected + " bytes, received " + copied);
            }
            if (archive.exists() && !archive.delete()) throw new IOException("Cannot replace previous Chrome archive.");
            if (!partial.renameTo(archive)) throw new IOException("Cannot finalize Chrome archive download.");
            appendLog("Chrome archive downloaded: " + copied + " bytes");
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    private static void extractChrome(File rootDir, File rootfs) throws IOException {
        File archive = new File(rootDir, "chrome-headless-shell-" + CHROME_VERSION + ".zip");
        if (!archive.isFile() || archive.length() < 1_000_000L) {
            throw new IOException("Chrome archive is missing or too small.");
        }
        File base = new File(rootfs, "opt/chrome");
        deleteRecursive(base);
        if (!base.mkdirs() && !base.isDirectory()) throw new IOException("Cannot create /opt/chrome in rootfs.");
        String canonicalBase = base.getCanonicalPath() + File.separator;
        long totalBytes = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(base, entry.getName());
                String canonicalTarget = target.getCanonicalPath();
                if (!canonicalTarget.startsWith(canonicalBase)) {
                    throw new IOException("Unsafe path in Chrome archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Cannot create " + target);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                        totalBytes += count;
                        if (totalBytes > 700L * 1024L * 1024L) throw new IOException("Chrome archive expanded beyond safety limit.");
                    }
                }
                target.setReadable(true, false);
                String name = target.getName();
                if ("chrome-headless-shell".equals(name) || "chrome_crashpad_handler".equals(name)
                        || "chrome-sandbox".equals(name)) {
                    target.setExecutable(true, false);
                }
                entries++;
                zip.closeEntry();
            }
        }
        File executable = new File(rootfs, CHROME_EXECUTABLE.substring(1));
        if (!executable.isFile() || executable.length() < 10_000_000L) {
            throw new IOException("Chrome extraction did not produce the expected executable: " + executable);
        }
        executable.setExecutable(true, false);
        appendLog("Chrome extracted: entries=" + entries + ", bytes=" + totalBytes + ", executable=" + executable.length());
        if (!archive.delete()) appendLog("Warning: could not delete Chrome ZIP archive; it can be removed to free space.");
    }

    /** Write usable DNS servers into the Ubuntu rootfs before running apt.
     * The Android app has working networking, but a fresh Ubuntu Base rootfs can
     * contain an empty or dangling /etc/resolv.conf symlink. */
    private static void prepareRootfsDns(Context context, File rootfs) throws IOException {
        File etc = new File(rootfs, "etc");
        if (!etc.isDirectory() && !etc.mkdirs()) {
            throw new IOException("Cannot create Ubuntu /etc for DNS resolver.");
        }
        File resolv = new File(etc, "resolv.conf");
        try {
            if (java.nio.file.Files.isSymbolicLink(resolv.toPath())) {
                java.nio.file.Files.deleteIfExists(resolv.toPath());
                appendLog("Removed Ubuntu /etc/resolv.conf symlink so ASTRA can provide the Android DNS configuration.");
            }
        } catch (Exception e) {
            throw new IOException("Cannot replace Ubuntu /etc/resolv.conf: " + safe(e.getMessage()), e);
        }

        java.util.LinkedHashSet<String> servers = new java.util.LinkedHashSet<>();
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = manager == null ? null : manager.getActiveNetwork();
            LinkProperties properties = manager == null || active == null
                    ? null : manager.getLinkProperties(active);
            if (properties != null && properties.getDnsServers() != null) {
                for (java.net.InetAddress dns : properties.getDnsServers()) {
                    if (dns == null || dns.isAnyLocalAddress() || dns.isLoopbackAddress()
                            || dns.isLinkLocalAddress()) continue;
                    String address = dns.getHostAddress();
                    if (address != null && !address.isEmpty() && !address.contains("%")) {
                        servers.add(address);
                    }
                }
            }
        } catch (Exception e) {
            appendLog("Could not read Android network DNS settings: " + e.getClass().getSimpleName()
                    + "; using public DNS fallbacks.");
        }
        // Keep public fallbacks after Android's current-network resolvers.
        servers.add("1.1.1.1");
        servers.add("8.8.8.8");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(resolv, false))) {
            writer.write("# Managed by ASTRA; generated from Android network DNS.\n");
            writer.write("options timeout:2 attempts:2 rotate\n");
            for (String server : servers) {
                writer.write("nameserver ");
                writer.write(server);
                writer.write('\n');
            }
        }
        if (!resolv.isFile() || resolv.length() < 1L) {
            throw new IOException("Ubuntu /etc/resolv.conf was not created.");
        }
        appendLog("Ubuntu DNS resolver configured with " + servers.size()
                + " nameserver entries (Android network DNS first, public fallbacks after).");
    }

    private static void installRuntimeDependencies(Context context) throws Exception {
        String packages = "libnss3 libnspr4 libglib2.0-0t64 libdbus-1-3 " +
                "libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64 " +
                "libcups2t64 libdrm2 libxkbcommon0 libx11-6 libx11-xcb1 libxcb1 " +
                "libxcomposite1 libxdamage1 libxext6 libxfixes3 libxrandr2 libxshmfence1 " +
                "libgbm1 libpango-1.0-0 libcairo2 libfontconfig1 libfreetype6 " +
                "libasound2t64 libgtk-3-0t64 libxrender1 libxi6 libxtst6 libxcursor1 " +
                "libxss1 libegl1 libgles2 libgl1 libvulkan1 libpci3 libudev1 fonts-liberation";
        String script =
                "set -u; export DEBIAN_FRONTEND=noninteractive; " +
                "echo ASTRA_DNS_RESOLV_CONF; cat /etc/resolv.conf 2>&1; " +
                "echo ASTRA_DNS_PROBE_PORTS_UBUNTU; " +
                "if ! getent hosts ports.ubuntu.com; then echo ASTRA_DNS_RESOLUTION_FAILED; exit 39; fi; " +
                "echo ASTRA_DNS_RESOLUTION_OK; " +
                "apt-get clean >/dev/null 2>&1 || true; " +
                "rm -rf /var/lib/apt/lists/*; mkdir -p /var/lib/apt/lists/partial; chmod 755 /var/lib/apt/lists /var/lib/apt/lists/partial; " +
                // Use Ubuntu's standard HTTP mirror. apt still verifies signed InRelease/Release metadata;
                // this avoids disabling TLS checks in a minimal rootfs without installed CA certificates.
                "sed -i 's|https://ports.ubuntu.com/ubuntu-ports/|http://ports.ubuntu.com/ubuntu-ports/|g' /etc/apt/sources.list.d/ubuntu.sources; " +
                "sed -i 's|http://ports.ubuntu.com/ubuntu-ports/|http://ports.ubuntu.com/ubuntu-ports/|g' /etc/apt/sources.list.d/ubuntu.sources; " +
                "echo ASTRA_APT_UPDATE_HTTP_START; " +
                "apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::http::Timeout=30 update >/tmp/astra-apt-update-http.log 2>&1; U=$?; cat /tmp/astra-apt-update-http.log; " +
                "INDEX_COUNT=$(find /var/lib/apt/lists -type f -name '*Packages*' ! -path '*/partial/*' | wc -l); " +
                "PACKAGE_CHECK=1; for p in ca-certificates curl unzip libnss3 libgbm1 libgtk-3-0t64; do " +
                "if ! apt-cache show \"$p\" >/dev/null 2>&1; then PACKAGE_CHECK=0; echo ASTRA_MISSING_PACKAGE_CANDIDATE=$p; fi; done; " +
                "echo ASTRA_APT_HTTP_RESULT=exit:$U indexes:$INDEX_COUNT candidates:$PACKAGE_CHECK; " +
                "if [ \"$INDEX_COUNT\" -lt 1 ] || [ \"$PACKAGE_CHECK\" -eq 0 ]; then " +
                "echo ASTRA_APT_HTTP_INCOMPLETE_TRYING_HTTPS_WITH_DEFAULT_CERTIFICATE_VALIDATION; " +
                "sed -i 's|http://ports.ubuntu.com/ubuntu-ports/|https://ports.ubuntu.com/ubuntu-ports/|g' /etc/apt/sources.list.d/ubuntu.sources; " +
                "rm -rf /var/lib/apt/lists/*; mkdir -p /var/lib/apt/lists/partial; chmod 755 /var/lib/apt/lists /var/lib/apt/lists/partial; " +
                "apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::https::Timeout=30 update >/tmp/astra-apt-update-https.log 2>&1; H=$?; cat /tmp/astra-apt-update-https.log; " +
                "INDEX_COUNT=$(find /var/lib/apt/lists -type f -name '*Packages*' ! -path '*/partial/*' | wc -l); " +
                "PACKAGE_CHECK=1; for p in ca-certificates curl unzip libnss3 libgbm1 libgtk-3-0t64; do " +
                "if ! apt-cache show \"$p\" >/dev/null 2>&1; then PACKAGE_CHECK=0; echo ASTRA_MISSING_PACKAGE_CANDIDATE_HTTPS=$p; fi; done; " +
                "echo ASTRA_APT_HTTPS_RESULT=exit:$H indexes:$INDEX_COUNT candidates:$PACKAGE_CHECK; " +
                "if [ \"$INDEX_COUNT\" -lt 1 ] || [ \"$PACKAGE_CHECK\" -eq 0 ]; then echo ASTRA_APT_INDEX_OR_CANDIDATE_CHECK_FAILED; exit 40; fi; " +
                "fi; " +
                "apt-cache policy ca-certificates curl unzip libnss3 libgbm1 libgtk-3-0t64; " +
                "echo ASTRA_CHROME_DEPENDENCY_INSTALL_START; " +
                "apt-get install -y --no-install-recommends " + packages + " >/tmp/astra-apt-install.log 2>&1 " +
                "|| { echo ASTRA_APT_INSTALL_FAILED; cat /tmp/astra-apt-install.log; exit 42; }; " +
                "cat /tmp/astra-apt-install.log; " +
                "ldd " + CHROME_EXECUTABLE + " >/tmp/astra-chrome-ldd.log 2>&1 || true; cat /tmp/astra-chrome-ldd.log; " +
                "if grep -q 'not found' /tmp/astra-chrome-ldd.log; then echo ASTRA_CHROME_HAS_MISSING_SHARED_LIBRARIES; exit 43; fi; " +
                "apt-get clean >/dev/null 2>&1 || true; rm -rf /var/lib/apt/lists/*; mkdir -p /var/lib/apt/lists/partial; " +
                "mkdir -p /opt/chrome; touch /opt/chrome/.astra_deps_ready; echo CHROME_DEPENDENCIES_OK";
        int exit = runProotScript(context, "Ubuntu DNS + APT + Chrome dependency setup", script);
        if (exit != 0) {
            String reason = exit == 39 ? "Ubuntu could not resolve ports.ubuntu.com even after ASTRA wrote resolv.conf" :
                    "Ubuntu DNS/Chrome dependency setup failed (exit=" + exit + ")";
            throw new IOException(reason + ". Read http://127.0.0.1:18765/headless/log.");
        }
    }

    private static void launchChrome(Context context) throws Exception {
        if (isCdpOnline()) return;
        File proroot = new File(context.getApplicationInfo().nativeLibraryDir, "libproroot.so");
        File rootfs = ProrootRuntime.rootfsDir(context);
        if (!proroot.isFile()) throw new IOException("libproroot.so is missing from APK nativeLibraryDir.");
        String command =
                "export HOME=/root; export XDG_CONFIG_HOME=/root/.config; export XDG_CACHE_HOME=/root/.cache; export TMPDIR=/tmp; " +
                "mkdir -p /root/.astra/loremotion-profile /root/.config /root/.cache /tmp; " +
                "exec " + CHROME_EXECUTABLE +
                " --headless --no-sandbox --disable-gpu --disable-dev-shm-usage" +
                " --disable-crash-reporter --disable-breakpad --disable-background-networking" +
                " --disable-default-apps --no-first-run --no-default-browser-check" +
                " --password-store=basic --use-mock-keychain" +
                " --remote-debugging-address=127.0.0.1 --remote-debugging-port=" + CDP_PORT +
                " --remote-allow-origins='*'" +
                " --user-data-dir=/root/.astra/loremotion-profile" +
                " --window-size=1280,720 https://loremotion.com/generate/";
        Process p = new ProcessBuilder(
                proroot.getAbsolutePath(),
                "-r", rootfs.getAbsolutePath(),
                "-0", "--link2symlink", "-w", "/root",
                "/bin/sh", "-c", command
        ).redirectErrorStream(true).start();
        chromeProcess = p;
        Thread logPump = new Thread(() -> copyProcessOutput(p), "ASTRA-Chrome-Log");
        logPump.setDaemon(true);
        logPump.start();
        appendLog("Started Proroot/Chrome process; awaiting CDP readiness.");
    }

    private static int runProotScript(Context context, String label, String script) throws Exception {
        File proroot = new File(context.getApplicationInfo().nativeLibraryDir, "libproroot.so");
        File rootfs = ProrootRuntime.rootfsDir(context);
        if (!proroot.isFile()) throw new IOException("libproroot.so not found: " + proroot);
        appendLog("--- " + label + " ---");
        Process p = new ProcessBuilder(
                proroot.getAbsolutePath(),
                "-r", rootfs.getAbsolutePath(),
                "-0", "--link2symlink", "-w", "/root",
                "/bin/sh", "-c", script
        ).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) appendLog(line);
        }
        int exit = p.waitFor();
        appendLog("--- " + label + " exit=" + exit + " ---");
        return exit;
    }

    private static void copyProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) appendLog("[chrome] " + line);
        } catch (Exception e) {
            appendLog("Chrome log reader: " + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
        }
    }

    private static boolean isCdpOnline() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + CDP_PORT + "/json/version").openConnection();
            connection.setConnectTimeout(700);
            connection.setReadTimeout(700);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            String version = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return version.contains("HeadlessChrome/");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static String openUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return "{\"ok\":false,\"error\":\"Only http/https URLs are allowed\"}";
        }
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(url, "UTF-8");
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + CDP_PORT + "/json/new?" + encoded).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("PUT");
            connection.setDoOutput(false);
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (in == null) return "{\"ok\":false,\"error\":\"CDP HTTP " + code + "\"}";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream source = in) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = source.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) return "{\"ok\":false,\"http\":" + code + ",\"response\":\"" + json(body) + "\"}";
            return body;
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + json(e.getClass().getSimpleName() + ": " + safe(e.getMessage())) + "\"}";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static String getState() { return state; }

    public static String getSummary() {
        return state + "\n" + detail + "\nLocal API: 127.0.0.1:18765\nCDP: 127.0.0.1:" + CDP_PORT;
    }

    public static String statusJson() {
        String s = state;
        return "{\"ok\":" + "ONLINE".equals(s) +
                ",\"state\":\"" + json(s) + "\",\"detail\":\"" + json(detail) +
                "\",\"cdp\":\"http://127.0.0.1:" + CDP_PORT + "/json/version\"" +
                ",\"profile\":\"/root/.astra/loremotion-profile\",\"persistentProfile\":true}";
    }

    public static String logJson() {
        Context c = appContext;
        if (c == null) c = MainApplication.context();
        String log = c == null ? "Runtime has not started." : readTail(new File(ProrootRuntime.rootDir(c), "headless-runtime.log"), 18000);
        return "{\"ok\":true,\"state\":\"" + json(state) + "\",\"log\":\"" + json(log) + "\"}";
    }

    private static void appendLog(String message) {
        Context c = appContext;
        if (c == null) c = MainApplication.context();
        if (c == null) return;
        try {
            File dir = ProrootRuntime.rootDir(c);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "headless-runtime.log");
            if (file.exists() && file.length() > 2L * 1024L * 1024L) {
                File old = new File(dir, "headless-runtime.log.1");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
                writer.write(java.text.DateFormat.getTimeInstance().format(new java.util.Date()));
                writer.write(" ");
                writer.write(message == null ? "" : message);
                writer.newLine();
            }
        } catch (Exception ignored) { }
    }

    private static String readTail(File file, int maxChars) {
        if (file == null || !file.isFile()) return "No runtime log yet.";
        try (FileInputStream in = new FileInputStream(file)) {
            long length = file.length();
            long skip = Math.max(0L, length - maxChars * 2L);
            while (skip > 0L) {
                long n = in.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return text.length() > maxChars ? text.substring(text.length() - maxChars) : text;
        } catch (Exception e) {
            return "Cannot read runtime log: " + e.getClass().getSimpleName() + ": " + safe(e.getMessage());
        }
    }

    private static void deleteRecursive(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Cannot delete " + file);
    }

    private static String json(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\b", "\\b").replace("\f", "\\f")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String safe(String value) { return value == null || value.isEmpty() ? "(no details)" : value; }

}
