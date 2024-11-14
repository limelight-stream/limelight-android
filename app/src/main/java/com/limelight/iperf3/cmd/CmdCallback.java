package com.limelight.iperf3.cmd;


import com.limelight.iperf3.Iperf3Callback;

/**
 *
 * @author shenyong
 * @date 2020/12/3
 */
public interface CmdCallback extends Iperf3Callback {

    void onRawOutput(String rawOutputLine);
}