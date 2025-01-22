package com.limelight.nvstream;

import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.jni.MoonBridge;

public class StreamConfiguration {
    public static final int INVALID_APP_ID = 0;

    public static final int STREAM_CFG_LOCAL = 0;
    public static final int STREAM_CFG_REMOTE = 1;
    public static final int STREAM_CFG_AUTO = 2;
    
    private NvApp app;
    private int width, height;
    private int refreshRate;
    private int launchRefreshRate;
    private int clientRefreshRateX100;
    private int bitrate;
    private boolean sops;
    private boolean enableAdaptiveResolution;
    private boolean playLocalAudio;
    private int maxPacketSize;
    private int remote;
    private MoonBridge.AudioConfiguration audioConfiguration;
    private int supportedVideoFormats;
    private int attachedGamepadMask;
    private int encryptionFlags;
    private int colorRange;
    private int colorSpace;
    private boolean persistGamepadsAfterDisconnect;

    public static class Builder {
        private StreamConfiguration config = new StreamConfiguration();
        
        public StreamConfiguration.Builder setApp(NvApp app) {
            config.app = app;
            return this;
        }
        
        public StreamConfiguration.Builder setRemoteConfiguration(int remote) {
            config.remote = remote;
            return this;
        }
        
        public StreamConfiguration.Builder setResolution(int width, int height) {
            config.width = width;
            config.height = height;
            return this;
        }
        
        public StreamConfiguration.Builder setRefreshRate(int refreshRate) {
            config.refreshRate = refreshRate;
            return this;
        }

        public StreamConfiguration.Builder setLaunchRefreshRate(int refreshRate) {
            config.launchRefreshRate = refreshRate;
            return this;
        }
        
        public StreamConfiguration.Builder setBitrate(int bitrate) {
            config.bitrate = bitrate;
            return this;
        }
        
        public StreamConfiguration.Builder setEnableSops(boolean enable) {
            config.sops = enable;
            return this;
        }
        
        public StreamConfiguration.Builder enableAdaptiveResolution(boolean enable) {
            config.enableAdaptiveResolution = enable;
            return this;
        }
        
        public StreamConfiguration.Builder enableLocalAudioPlayback(boolean enable) {
            config.playLocalAudio = enable;
            return this;
        }
        
        public StreamConfiguration.Builder setMaxPacketSize(int maxPacketSize) {
            config.maxPacketSize = maxPacketSize;
            return this;
        }

        public StreamConfiguration.Builder setAttachedGamepadMask(int attachedGamepadMask) {
            config.attachedGamepadMask = attachedGamepadMask;
            return this;
        }

        public StreamConfiguration.Builder setAttachedGamepadMaskByCount(int gamepadCount) {
            config.attachedGamepadMask = 0;
            for (int i = 0; i < 4; i++) {
                if (gamepadCount > i) {
                    config.attachedGamepadMask |= 1 << i;
                }
            }
            return this;
        }

        public StreamConfiguration.Builder setPersistGamepadsAfterDisconnect(boolean value) {
            config.persistGamepadsAfterDisconnect = value;
            return this;
        }

        public StreamConfiguration.Builder setClientRefreshRateX100(int refreshRateX100) {
            config.clientRefreshRateX100 = refreshRateX100;
            return this;
        }

        public StreamConfiguration.Builder setAudioConfiguration(MoonBridge.AudioConfiguration audioConfig) {
            config.audioConfiguration = audioConfig;
            return this;
        }
        
        public StreamConfiguration.Builder setSupportedVideoFormats(int supportedVideoFormats) {
            config.supportedVideoFormats = supportedVideoFormats;
            return this;
        }

        public StreamConfiguration.Builder setColorRange(int colorRange) {
            config.colorRange = colorRange;
            return this;
        }

        public StreamConfiguration.Builder setColorSpace(int colorSpace) {
            config.colorSpace = colorSpace;
            return this;
        }

        public StreamConfiguration build() {
            return config;
        }
    }
    
    private StreamConfiguration() {
        // Set default attributes
        this.app = new NvApp("Steam");
        this.width = 1280;
        this.height = 720;
        this.refreshRate = 60;
        this.launchRefreshRate = 60;
        this.bitrate = 10000;
        this.maxPacketSize = 1024;
        this.remote = STREAM_CFG_AUTO;
        this.sops = true;
        this.enableAdaptiveResolution = false;
        this.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        this.supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        this.attachedGamepadMask = 0;
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }
    
    public int getRefreshRate() {
        return refreshRate;
    }

    public int getLaunchRefreshRate() {
        return launchRefreshRate;
    }
    
    public int getBitrate() {
        return bitrate;
    }
    
    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public NvApp getApp() {
        return app;
    }
    
    public boolean getSops() {
        return sops;
    }
    
    public boolean getAdaptiveResolutionEnabled() {
        return enableAdaptiveResolution;
    }
    
    public boolean getPlayLocalAudio() {
        return playLocalAudio;
    }
    
    public int getRemote() {
        return remote;
    }

    public MoonBridge.AudioConfiguration getAudioConfiguration() {
        return audioConfiguration;
    }
    
    public int getSupportedVideoFormats() {
        return supportedVideoFormats;
    }

    public int getAttachedGamepadMask() {
        return attachedGamepadMask;
    }

    public boolean getPersistGamepadsAfterDisconnect() {
        return persistGamepadsAfterDisconnect;
    }

    public int getClientRefreshRateX100() {
        return clientRefreshRateX100;
    }

    public int getColorRange() {
        return colorRange;
    }

    public int getColorSpace() {
        return colorSpace;
    }
}
