package com.limelight.iperf3.cmd;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author shenyong
 * @date 2020-11-10
 */
public class Iperf3Cmd {
    
    public static final String TAG = "Iperf3Cmd";

    private final Context context;
    private final CmdCallback callback;

    public Iperf3Cmd(Context context, CmdCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    private static final String EXECUTABLE_NAME = "libIperf3.so";

    private final Pattern CONNECTING_PATTERN = Pattern.compile("(Connecting to host (.*), port (\\d+))");
    private final Pattern CONNECTED_PATTERN = Pattern.compile("(local (.*) port (\\d+) connected to (.*) port (\\d+))");
    private final Pattern REPORT_PATTERN = Pattern.compile("(\\d{1,2}.\\d{2})-(\\d{1,2}.\\d{2})\\s+sec" +
            "\\s+(\\d+(.\\d+)?) [KMGT]?Bytes\\s+(\\d+(.\\d+)?) Mbits/sec");
    private final Pattern UDP_LOSS = Pattern.compile("\\d+/\\d+ \\([\\d+-.e]+%\\)");
    private final Pattern TITLE_PATTERN = Pattern.compile("\\[\\s+ID\\]");
    private final Pattern ERR_PATTERN = Pattern.compile("iperf3: error");

    private int parallels = 0;
    private boolean isDown = false;
    // title出现次数。title栏输出第一次之后的、第二次之前的，是中间结果，第二次之后的是最终平均速率
    private int titleCnt = 0;

    private String getCmdPath() {
        return context.getApplicationInfo().nativeLibraryDir + "/" + EXECUTABLE_NAME;
    }

    public void exec(String[] args) {
        new Thread(() -> {
            String[] cmdAndArgs = new String[args.length + 1];
            cmdAndArgs[0] = getCmdPath();
            System.arraycopy(args, 0, cmdAndArgs, 1, args.length);

            try {
//                execCommand("ls -l " + context.getApplicationInfo().nativeLibraryDir);
                Process process = Runtime.getRuntime().exec(cmdAndArgs);
                Log.i(TAG, "command: " + String.join(" ", cmdAndArgs));
                parseArgs(cmdAndArgs);

                try (
                        InputStreamReader in = new InputStreamReader(process.getInputStream());
                        BufferedReader outReader = new BufferedReader(in);
                        BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                ) {
                    String line;
                    while ((line = outReader.readLine()) != null) {
                        parseToCallback(line);
                    }
                    while ((line = errReader.readLine()) != null) {
                        parseToCallback(line);
                    }

                    // 等待进程结束
                    process.waitFor();
                    Log.i(TAG, "exitValue: " + process.waitFor());
                }

            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    private void parseArgs(final String[] cmdAndArgs) {
        isDown = false;
        titleCnt = 0;
        for (int i = 0; i < cmdAndArgs.length; i++) {
            String s = cmdAndArgs[i];
            if ("-P".equals(s)) {
                parallels = Integer.parseInt(cmdAndArgs[i + 1]);
            } else if ("-R".equals(s)) {
                isDown = true;
            }
        }
        // 如果命令中未设置-P参数，取默认值0
        // -P， --parallel n	要与服务器建立的同时连接数。默认值为 1。
        if(parallels == 0) {
            parallels = 1;
        }
    }

    private void parseToCallback(String line) {
        Log.d(TAG, "output: " +  line);
        callback.onRawOutput(line);
        if (TITLE_PATTERN.matcher(line).find()) {
            titleCnt++;
        }
        Matcher mr = CONNECTING_PATTERN.matcher(line);
        if (mr.find()) {
            String addr = mr.group(2);
            int port = Integer.parseInt(mr.group(3));
            callback.onConnecting(addr, port);
        }
        mr = CONNECTED_PATTERN.matcher(line);
        if (mr.find()) {
            String laddr = mr.group(2);
            int lport = Integer.parseInt(mr.group(3));
            String raddr = mr.group(4);
            int rport = Integer.parseInt(mr.group(5));
            callback.onConnected(laddr, lport, raddr, rport);
        }
        // 并发连接数为1和>1时，速率报告有以下两种格式，通过正则捕获组来截取数据
        // [  4]   9.00-10.00  sec  2.18 MBytes  18.3 Mbits/sec
        // [SUM]   9.00-10.00  sec  1.85 MBytes  15.5 Mbits/sec
        mr = REPORT_PATTERN.matcher(line);
        if (mr.find()) {
            double st = Double.parseDouble(mr.group(1));
            double et = Double.parseDouble(mr.group(2));
            double trans = Double.parseDouble(mr.group(3));
            double bw = Double.parseDouble(mr.group(5));
            if (isInterval(line)) {
                callback.onInterval(st, et, trans, bw);
            } else if (isResult(line)) {
                callback.onResult(st, et, trans, bw);
            }
        }
        if (ERR_PATTERN.matcher(line).find()) {
            callback.onError(line);
        }
    }

    private boolean isInterval(String line) {
        return isTcpInterval(line) || isUdpInterval(line);
    }

    private boolean isResult(String line) {
        return isTcpResult(line) || isUdpResult(line);
    }

    private boolean isTcpInterval(String line) {
        return titleCnt == 1
                && ((parallels == 1)
                || (parallels > 1 && line.startsWith("[SUM]")));
    }

    private boolean isTcpResult(String line) {
        //eg:
        //[ ID] Interval           Transfer     Bandwidth       Retr
        //[  4]   0.00-10.00  sec  19.5 MBytes  16.3 Mbits/sec   19             sender
        //[  4]   0.00-10.00  sec  19.0 MBytes  15.9 Mbits/sec                  receiver
        boolean isLocalResult = (isDown && line.contains("receiver"))
                || (!isDown && line.contains("sender"));
        return titleCnt > 1 && isLocalResult
                && ((parallels == 1)
                || (parallels > 1 && line.startsWith("[SUM]")));
    }

    private boolean isUdpInterval(String line) {
        // parallels == 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Total Datagrams
        //[  4]   0.00-1.00   sec  9.86 MBytes  82.7 Mbits/sec  1262
        // parallels > 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Total Datagrams
        //[SUM]   0.00-1.00   sec  10.6 MBytes  88.5 Mbits/sec  1352
        boolean isUdpUpInterval = (titleCnt == 1 && !UDP_LOSS.matcher(line).find())
                && ((parallels == 1)
                || (parallels > 1 && line.startsWith("[SUM]")));
        // parallels == 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Jitter    Lost/Total Datagrams
        //[  4]   0.00-1.00   sec   240 KBytes  1.96 Mbits/sec  2594.444 ms  12595/12625 (1e+02%)
        // parallels > 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Jitter    Lost/Total Datagrams
        //[SUM]   0.00-1.00   sec   264 KBytes  2.16 Mbits/sec  6458.650 ms  25698/25731 (1e+02%)
        boolean inUdpDownInterval = (titleCnt == 1 && UDP_LOSS.matcher(line).find())
                && ((parallels == 1)
                || (parallels > 1 && line.startsWith("[SUM]")));
        return isUdpUpInterval || inUdpDownInterval;
    }

    private boolean isUdpResult(String line) {
        //-------- up --------
        // parallels == 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Jitter    Lost/Total Datagrams
        //[  4]   0.00-10.00  sec  95.3 MBytes  80.0 Mbits/sec  2.963 ms  11168/12029 (93%)
        // parallels > 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Jitter    Lost/Total Datagrams
        //[SUM]   0.00-10.00  sec   104 MBytes  86.9 Mbits/sec  7.764 ms  11935/12613 (95%)
        //-------- down --------
        // parallels == 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Jitter    Lost/Total Datagrams
        //[  4]   0.00-10.00  sec  1.16 GBytes   996 Mbits/sec  4.518 ms  151121/151406 (1e+02%)
        // parallels > 1 eg:
        //[ ID] Interval           Transfer     Bandwidth       Jitter    Lost/Total Datagrams
        //[SUM]   0.00-10.00  sec  2.33 GBytes  2002 Mbits/sec  55.278 ms  299008/299247 (1e+02%)
        return (titleCnt > 1 && UDP_LOSS.matcher(line).find())
                && ((parallels == 1)
                || (parallels > 1 && line.startsWith("[SUM]")));
    }

    public void execCommand(String command) {
        Log.d(TAG, "exec: " + command);
        try {
            Runtime runtime = Runtime.getRuntime();
            Process proc = runtime.exec(command);

            BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

            // 读取标准输出流
            String s;
            while ((s = stdInput.readLine()) != null) {
                Log.d(TAG, s);
            }

            // 读取标准错误流
            while ((s = stdError.readLine()) != null) {
                Log.d(TAG, "Error: " + s);
            }

            // 等待进程结束
            proc.waitFor();

            // 打印退出值
            Log.d(TAG, "exit value = " + proc.exitValue());
        } catch (Exception e) {
            Log.d(TAG, "Exception: " + e.getMessage());
            Log.e(TAG, e.getMessage(), e);
        }
    }
}