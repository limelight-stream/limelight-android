package com.limelight.iperf3;

/**
 * @author shenyong
 * @date 2020-11-17
 */
public interface Iperf3Callback {

    void onConnecting(String destHost, int destPort);

    void onConnected(String localAddr, int localPort, String destAddr, int destPort);

    void onInterval(double timeStart, double timeEnd, double transfer, double bitrate);

    void onResult(double timeStart, double timeEnd, double transfer, double bitrate);

    void onError(String errMsg);
}
