package com.utkarsh2573.speedtest.service;

import com.utkarsh2573.speedtest.model.SpeedTestResult;
import fr.bmartel.speedtest.SpeedTestReport;
import fr.bmartel.speedtest.SpeedTestSocket;
import fr.bmartel.speedtest.inter.ISpeedTestListener;
import fr.bmartel.speedtest.model.SpeedTestError;
import fr.bmartel.speedtest.model.SpeedTestMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;

public class SpeedTestService {

    public SpeedTestResult runTest() throws InterruptedException {
        SpeedTestSocket socket = new SpeedTestSocket();
        SpeedTestResult result = new SpeedTestResult();
        CountDownLatch latch = new CountDownLatch(2);

        socket.addSpeedTestListener(new ISpeedTestListener() {
            @Override
            public void onCompletion(SpeedTestReport report) {
                BigDecimal bitsPerSec = report.getTransferRateBit(); // already a BigDecimal
                BigDecimal mbps = bitsPerSec.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP);

                if (report.getSpeedTestMode() == SpeedTestMode.DOWNLOAD) {
                    result.setDownloadMbps(mbps.doubleValue());
                } else if (report.getSpeedTestMode() == SpeedTestMode.UPLOAD) {
                    result.setUploadMbps(mbps.doubleValue());
                }

                latch.countDown();
            }

            @Override
            public void onError(SpeedTestError error, String msg) {
                System.err.println("SpeedTest Error: " + msg);
                latch.countDown();
            }

            @Override
            public void onProgress(float percent, SpeedTestReport report) {
                // Can update progress UI if needed
            }
        });

        // Start download test
        socket.startDownload("http://speedtest.tele2.net/10MB.zip");
        Thread.sleep(15_000);
        socket.startUpload("http://speedtest.tele2.net/upload.php", 5_000_000);

        latch.await(); // wait for both tests to complete

        // Measure ping separately
        long pingMs = measurePing("8.8.8.8");
        result.setPingMs(pingMs);

        return result;
    }

    private long measurePing(String host) {
        try {
            InetAddress inet = InetAddress.getByName(host);
            long start = System.currentTimeMillis();
            boolean reachable = inet.isReachable(3000); // 3 seconds timeout
            long end = System.currentTimeMillis();

            return reachable ? (end - start) : -1;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }
}
