package com.limelight.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 *
 * @author shenyong
 * @date 2020-11-10
 */
public class DevUtils {
    public static String abiInfo = "";
    public static String getCpuApi() {
        if (abiInfo.isEmpty()) {
            try (
                    BufferedReader outReader = new BufferedReader(new InputStreamReader(
                            Runtime.getRuntime().exec("getprop ro.product.cpu.abi").getInputStream()));
                    ) {
                abiInfo = outReader.readLine();
            } catch (Exception ignored) {

            };
        }
        return abiInfo;
    }

    public static boolean isArmAbi() {
        return getCpuApi().startsWith("armeabi");
    }

    public static boolean isArm64Abi() {
        return getCpuApi().startsWith("arm64");
    }

    public static boolean isX86Abi() {
        return getCpuApi().startsWith("x86");
    }
}