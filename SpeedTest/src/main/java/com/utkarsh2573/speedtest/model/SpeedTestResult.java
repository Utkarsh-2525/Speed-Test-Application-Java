package com.utkarsh2573.speedtest.model;

public class SpeedTestResult {
    private double downloadMbps;
    private double uploadMbps;
    private long pingMs;

    // Getters and setters
    public double getDownloadMbps() { return downloadMbps; }
    public void setDownloadMbps(double downloadMbps) { this.downloadMbps = downloadMbps; }

    public double getUploadMbps() { return uploadMbps; }
    public void setUploadMbps(double uploadMbps) { this.uploadMbps = uploadMbps; }

    public long getPingMs() { return pingMs; }
    public void setPingMs(long pingMs) { this.pingMs = pingMs; }
}
