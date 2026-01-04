package io.jenkins.plugins.wiz;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.GZIPOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

public class WizCliDownloaderTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private FilePath workspace;
    private TaskListener listener;
    private ByteArrayOutputStream logOutput;
    private java.util.Set<String> requestedUrls;

    private static final String VERSIONED_CLI_PATH = "/wizcli/1.2.3/wizcli-linux-amd64";
    private static final String LATEST_CLI_PATH = "/wizcli/latest/wizcli-linux-amd64";

    private static final byte[] CLI_CONTENT = "fake-wizcli-binary-content".getBytes(StandardCharsets.UTF_8);
    private static final String CLI_SHA256 = calculateSha256(CLI_CONTENT);
    private static final String FAKE_SIGNATURE = "-----BEGIN PGP SIGNATURE-----\nfake\n-----END PGP SIGNATURE-----";

    @Before
    public void setUp() throws Exception {
        requestedUrls = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(null);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        File workspaceDir = tempFolder.newFolder("workspace");
        workspace = new FilePath(workspaceDir);

        logOutput = new ByteArrayOutputStream();
        listener = new StreamTaskListener(logOutput, StandardCharsets.UTF_8);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testDownloadsUncompressedWhenVersionIsNotLatest() throws Exception {
        String cliPath = VERSIONED_CLI_PATH;
        setupUncompressedCliEndpoints(cliPath);

        WizCliSetup result = runWithMockedPGPAndInputValidator(baseUrl + cliPath, true);

        assertNotNull(result);
        assertTrue("Should have requested uncompressed CLI", requestedUrls.contains(cliPath));
        assertTrue("Should have requested sha256", requestedUrls.contains(cliPath + "-sha256"));
        assertTrue("Should have requested signature", requestedUrls.contains(cliPath + "-sha256.sig"));
        assertFalse("Should NOT have requested .gz", requestedUrls.contains(cliPath + ".gz"));

        FilePath cliFile = workspace.child("wizcli");
        assertTrue("CLI file should exist", cliFile.exists());
    }

    @Test
    public void testFallsBackToUncompressedWhenGzReturns403() throws Exception {
        String cliPath = LATEST_CLI_PATH;
        setupUncompressedCliEndpoints(cliPath);

        server.createContext(cliPath + ".gz", exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });

        WizCliSetup result = runWithMockedPGPAndInputValidator(baseUrl + cliPath, true);

        assertNotNull(result);
        assertTrue("Should have tried .gz first", requestedUrls.contains(cliPath + ".gz"));
        assertTrue("Should have fallen back to uncompressed CLI", requestedUrls.contains(cliPath));
        assertTrue("Should have requested sha256", requestedUrls.contains(cliPath + "-sha256"));
        assertFalse("Should NOT have requested .gz-sha256", requestedUrls.contains(cliPath + ".gz-sha256"));

        FilePath cliFile = workspace.child("wizcli");
        assertTrue("CLI file should exist", cliFile.exists());
    }

    @Test
    public void testFallsBackToUncompressedOnAnyHttpError() throws Exception {
        String cliPath = LATEST_CLI_PATH;
        setupUncompressedCliEndpoints(cliPath);

        server.createContext(cliPath + ".gz", exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        WizCliSetup result = runWithMockedPGPAndInputValidator(baseUrl + cliPath, true);

        assertNotNull(result);
        assertTrue("Should have tried .gz first", requestedUrls.contains(cliPath + ".gz"));
        assertTrue("Should have fallen back to uncompressed CLI", requestedUrls.contains(cliPath));

        FilePath cliFile = workspace.child("wizcli");
        assertTrue("CLI file should exist", cliFile.exists());
    }

    @Test
    public void testDownloadsCompressedWhenLatestAndGzAvailable() throws Exception {
        String cliPath = LATEST_CLI_PATH;
        setupUncompressedCliEndpoints(cliPath);
        setupCompressedCliEndpoints(cliPath);

        WizCliSetup result = runWithMockedPGPAndInputValidator(baseUrl + cliPath, true);

        assertNotNull(result);
        assertTrue("Should have requested .gz", requestedUrls.contains(cliPath + ".gz"));
        assertTrue("Should have requested .gz-sha256", requestedUrls.contains(cliPath + ".gz-sha256"));
        assertTrue("Should have requested .gz signature", requestedUrls.contains(cliPath + ".gz-sha256.sig"));
        assertTrue(
                "Should have requested uncompressed sha256 for final verification",
                requestedUrls.contains(cliPath + "-sha256"));
        assertFalse("Should NOT have requested uncompressed binary", requestedUrls.contains(cliPath));

        FilePath cliFile = workspace.child("wizcli");
        assertTrue("CLI file should exist", cliFile.exists());
    }

    @Test
    public void testFailsWhenPgpVerificationFails() throws Exception {
        String cliPath = VERSIONED_CLI_PATH;
        setupUncompressedCliEndpoints(cliPath);

        try {
            runWithMockedPGPAndInputValidator(baseUrl + cliPath, false);
            fail("Should have thrown an exception when PGP verification fails");
        } catch (hudson.AbortException e) {
            assertTrue(
                    "Exception message should mention GPG verification",
                    e.getMessage().contains("GPG signature verification failed"));
        }
    }

    @Test
    public void testSkipsDownloadWhenExistingCliIsValid() throws Exception {
        String cliPath = VERSIONED_CLI_PATH;
        setupUncompressedCliEndpoints(cliPath);

        FilePath cliFile = workspace.child("wizcli");
        cliFile.write(new String(CLI_CONTENT, StandardCharsets.UTF_8), StandardCharsets.UTF_8.name());

        WizCliSetup result = runWithMockedPGPAndInputValidator(baseUrl + cliPath, true);

        assertNotNull(result);
        assertTrue("Should have verified sha256", requestedUrls.contains(cliPath + "-sha256"));
        assertTrue("Should have verified signature", requestedUrls.contains(cliPath + "-sha256.sig"));
        assertFalse("Should NOT have downloaded CLI binary", requestedUrls.contains(cliPath));

        assertTrue("CLI file should exist", cliFile.exists());
    }

    private WizCliSetup runWithMockedPGPAndInputValidator(String wizCliURL, boolean pgpVerifies) throws Exception {
        ParsedWizCliUrl parsedUrl = new ParsedWizCliUrl(wizCliURL, WizCliVersion.V1);

        try (MockedStatic<WizInputValidator> mockedValidator = mockStatic(WizInputValidator.class);
                MockedConstruction<PGPVerifier> ignored =
                        mockConstruction(PGPVerifier.class, (mock, context) -> when(mock.verifySignatureFromFiles(
                                        anyString(), anyString(), anyString()))
                                .thenReturn(pgpVerifies))) {

            mockedValidator
                    .when(() -> WizInputValidator.parseWizCliUrl(anyString()))
                    .thenReturn(parsedUrl);

            return WizCliDownloader.setupWizCli(workspace, wizCliURL, listener);
        }
    }

    private void setupUncompressedCliEndpoints(String cliPath) {
        server.createContext(cliPath, exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            sendResponse(exchange, CLI_CONTENT);
        });

        server.createContext(cliPath + "-sha256", exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            sendResponse(exchange, CLI_SHA256.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext(cliPath + "-sha256.sig", exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            sendResponse(exchange, FAKE_SIGNATURE.getBytes(StandardCharsets.UTF_8));
        });
    }

    private void setupCompressedCliEndpoints(String cliPath) throws IOException {
        byte[] gzContent = gzip(CLI_CONTENT);
        String gzSha256 = calculateSha256(gzContent);

        server.createContext(cliPath + ".gz", exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else {
                sendResponse(exchange, gzContent);
            }
        });

        server.createContext(cliPath + ".gz-sha256", exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            sendResponse(exchange, gzSha256.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext(cliPath + ".gz-sha256.sig", exchange -> {
            requestedUrls.add(exchange.getRequestURI().getPath());
            sendResponse(exchange, FAKE_SIGNATURE.getBytes(StandardCharsets.UTF_8));
        });
    }

    private void sendResponse(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private static String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
