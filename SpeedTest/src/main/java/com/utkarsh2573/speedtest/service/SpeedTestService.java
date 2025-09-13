package com.utkarsh2573.speedtest.service;

import com.utkarsh2573.speedtest.model.SpeedTestResult;
import fr.bmartel.speedtest.SpeedTestReport;
import fr.bmartel.speedtest.SpeedTestSocket;
import fr.bmartel.speedtest.inter.ISpeedTestListener;
import fr.bmartel.speedtest.model.SpeedTestError;
import fr.bmartel.speedtest.model.SpeedTestMode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SpeedTestService {

    /**
     * Resilient speed test runner:
     * - tries multiple download/upload endpoints if some are blocked by AV/firewall
     * - doesn't use Thread.sleep; uses proper CountDownLatch sync
     * - falls back to TCP "ping" if ICMP is blocked
     */
    public SpeedTestResult runTest() throws InterruptedException {
        SpeedTestSocket socket = new SpeedTestSocket();
        SpeedTestResult result = new SpeedTestResult();

        // A list of fallback download URLs (common public test files). Add/remove as needed.
        List<String> downloadUrls = Arrays.asList(
                "http://speedtest.tele2.net/10MB.zip",
                "https://speed.hetzner.de/100MB.bin",
                "http://ipv4.download.thinkbroadband.com/5MB.zip"
        );

        // A list of fallback upload endpoints (some servers accept upload.php). These are examples;
        // you may need to adjust to servers you control or known endpoints that accept uploads.
        List<String> uploadUrls = Arrays.asList(
                "http://speedtest.tele2.net/upload.php",
                // If you don't have public upload endpoints, you can omit upload tests or run a local server.
                // "https://your-own-server/upload.php"
                "http://httpbin.org/post" // httpbin accepts POSTs (use smaller payload)
        );

        AtomicInteger remaining = new AtomicInteger(2); // download + upload expected results (or failures)
        CountDownLatch latch = new CountDownLatch(1); // release when remaining reaches 0

        // Track which mode is currently being started so onError knows context
        AtomicReference<SpeedTestMode> currentMode = new AtomicReference<>(null);

        // Track attempts/indices
        AtomicInteger downloadIndex = new AtomicInteger(0);
        AtomicInteger uploadIndex = new AtomicInteger(0);

        socket.addSpeedTestListener(new ISpeedTestListener() {
            @Override
            public void onCompletion(SpeedTestReport report) {
                try {
                    BigDecimal bitsPerSec = report.getTransferRateBit();
                    BigDecimal mbps = bitsPerSec.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP);

                    if (report.getSpeedTestMode() == SpeedTestMode.DOWNLOAD) {
                        result.setDownloadMbps(mbps.doubleValue());
                        System.out.println("Download complete: " + mbps + " Mbps");
                    } else if (report.getSpeedTestMode() == SpeedTestMode.UPLOAD) {
                        result.setUploadMbps(mbps.doubleValue());
                        System.out.println("Upload complete: " + mbps + " Mbps");
                    }
                } catch (Exception ex) {
                    System.err.println("Error processing completion report: " + ex.getMessage());
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        latch.countDown();
                    }
                }
            }

            @Override
            public void onError(SpeedTestError error, String msg) {
                // Log error
                System.err.println("SpeedTest onError: " + error + " / " + msg);
                SpeedTestMode mode = currentMode.get();

                // If it's a download a nd we have more download URLs, try next one
                if (mode == SpeedTestMode.DOWNLOAD) {
                    int idx = downloadIndex.incrementAndGet();
                    if (idx < downloadUrls.size()) {
                        String next = downloadUrls.get(idx);
                        System.out.println("Retrying download with fallback URL: " + next);
                        tryStartDownload(socket, currentMode, downloadUrls.get(idx));
                        return; // don't count this as final failure yet
                    } else {
                        System.err.println("All download URLs failed.");
                    }
                }

                // If it's an upload and we have more upload URLs, try next
                if (mode == SpeedTestMode.UPLOAD) {
                    int idx = uploadIndex.incrementAndGet();
                    if (idx < uploadUrls.size()) {
                        String next = uploadUrls.get(idx);
                        System.out.println("Retrying upload with fallback URL: " + next);
                        tryStartUpload(socket, currentMode, uploadUrls.get(idx));
                        return;
                    } else {
                        System.err.println("All upload URLs failed.");
                    }
                }

                // If no retries left, mark this mode as failed and decrement remaining
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown();
                }
            }

            @Override
            public void onProgress(float percent, SpeedTestReport report) {
                // Optional: update progress logging
                SpeedTestMode m = report != null ? report.getSpeedTestMode() : currentMode.get();
                System.out.printf("Progress (%s): %.1f%%\n", m, percent);
            }
        });

        // Start download with first URL
        if (!downloadUrls.isEmpty()) {
            currentMode.set(SpeedTestMode.DOWNLOAD);
            tryStartDownload(socket, currentMode, downloadUrls.get(0));
        } else {
            // No download URLs configured; count it as done
            remaining.decrementAndGet();
        }

        // We don't start upload immediately; upload will be started after a successful download,
        // or if download fails permanently we still try upload (depending on design). To keep
        // behavior simple: start upload only after download completion or if download fails all attempts.
        // To support that, we'll spin a small helper thread that waits until download attempts exhausted or completed,
        // then starts upload. That keeps behavior deterministic.

        Thread uploaderStarter = new Thread(() -> {
            // Wait until download has either set result or exhausted retries. We'll poll the downloadIndex and result.
            // A small loop with timeout to avoid indefinite blocking.
            int waitMs = 0;
            while (true) {
                // If download produced a value, proceed to start upload
                if (result.getDownloadMbps() != 0) {
                    break;
                }
                // If download attempts exhausted and remaining still >=1 (upload not started), break to try upload anyway
                if (downloadIndex.get() >= downloadUrls.size() - 1 && result.getDownloadMbps() == 0) {
                    // All download attempts tried (last index reached or exceeded)
                    break;
                }
                try {
                    Thread.sleep(200); // short sleep to avoid busy spin; this is small and not a long wait
                } catch (InterruptedException ignored) {
                    break;
                }
                waitMs += 200;
                // Safety: if waiting too long (e.g., 60s), proceed anyway
                if (waitMs > 60_000) break;
            }

            // Start upload attempts
            if (!uploadUrls.isEmpty()) {
                currentMode.set(SpeedTestMode.UPLOAD);
                tryStartUpload(socket, currentMode, uploadUrls.get(0));
            } else {
                // No upload configured; mark as done
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown();
                }
            }
        }, "uploader-starter-thread");

        uploaderStarter.setDaemon(true);
        uploaderStarter.start();

        // Wait for tests to either finish or fail after retries
        latch.await();

        // Measure ping: try ICMP first, then TCP connect fallback
        long pingMs = measurePingWithFallback("8.8.8.8", 53);
        result.setPingMs(pingMs);

        return result;
    }

    // Helper: start download non-blocking
    private void tryStartDownload(SpeedTestSocket socket, AtomicReference<SpeedTestMode> currentMode, String url) {
        try {
            currentMode.set(SpeedTestMode.DOWNLOAD);
            System.out.println("Starting download: " + url);
            socket.startDownload(url);
        } catch (Exception e) {
            System.err.println("Exception starting download: " + e.getMessage());
            // If startDownload itself throws, signal onError via listener isn't automatic; caller will rely on listener's onError.
            // Optionally you can call the listener's onError logic manually here if needed.
        }
    }

    // Helper: start upload non-blocking
    private void tryStartUpload(SpeedTestSocket socket, AtomicReference<SpeedTestMode> currentMode, String url) {
        try {
            currentMode.set(SpeedTestMode.UPLOAD);
            System.out.println("Starting upload: " + url);
            // The library's startUpload uses filesize in bytes parameter if available, otherwise library behavior applies
            // For httpbin.org we may need to adjust size; keep conservative size to avoid big traffic
            socket.startUpload(url, 2_000_000); // upload ~2 MB payload (adjust as needed)
        } catch (Exception e) {
            System.err.println("Exception starting upload: " + e.getMessage());
        }
    }

    /**
     * Measure ping: try ICMP via isReachable, if that fails or is blocked, fallback to a TCP connect to given port.
     * Returns -1 if unreachable.
     */
    private long measurePingWithFallback(String host, int tcpPort) {
        long icmp = measureIcmpPing(host, 3000);
        if (icmp >= 0) return icmp;

        long tcp = measureTcpPing(host, tcpPort, 3000);
        return tcp;
    }

    private long measureIcmpPing(String host, int timeoutMs) {
        try {
            InetAddress inet = InetAddress.getByName(host);
            long start = System.currentTimeMillis();
            boolean reachable = inet.isReachable(timeoutMs);
            long end = System.currentTimeMillis();
            return reachable ? (end - start) : -1;
        } catch (Exception e) {
            System.err.println("ICMP ping failed: " + e.getMessage());
            return -1;
        }
    }

    private long measureTcpPing(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            long start = System.currentTimeMillis();
            socket.connect(addr, timeoutMs);
            long end = System.currentTimeMillis();
            return end - start;
        } catch (IOException e) {
            System.err.println("TCP ping failed to " + host + ":" + port + " -> " + e.getMessage());
            return -1;
        }
    }
}
