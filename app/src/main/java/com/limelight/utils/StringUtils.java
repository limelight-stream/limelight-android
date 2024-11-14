package com.limelight.utils;

public class StringUtils {

    private static String[] BITRATE_UNITS = new String[]{"", "K", "M"};

    public static String byteps2BpsStr(long byteps) {
        return bps2BpsStr(byteps * 8);
    }

    public static String bps2BpsStr(long byteps) {
        float cur = byteps;
        int unitIndex = 0;
        while(cur >= 1000 && unitIndex < BITRATE_UNITS.length - 1) {
            cur = cur / 1000;
            unitIndex++;
        }
        return String.format((cur >= 10 || cur == 0 ? "%.0f" : "%.1f") + " %sbps", cur, BITRATE_UNITS[unitIndex]);
    }

    public static long preferNum(long number) {
        // 计算数字的位数
        int scale = (int) Math.floor(Math.log10(number)) + 1;

        // 根据位数确定步长
        int step = scale / 3; // 每三位确定一个步长
        if (step < 1) step = 1; // 至少有一个步长

        // 计算步长值
        long factor = (long) Math.pow(10, step);

        // 向上取整
        long rounded = ((number + factor - 1) / factor) * factor;

        // 特殊处理，如果取整后的数字与原数字差距过大，则调整步长
        if (rounded - number > factor * 2) {
            factor *= 2;
            rounded = ((number + factor - 1) / factor) * factor;
        }

        return rounded;
    }
}
