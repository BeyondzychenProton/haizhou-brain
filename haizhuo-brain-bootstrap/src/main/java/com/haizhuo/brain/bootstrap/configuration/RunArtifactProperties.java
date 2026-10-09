package com.haizhuo.brain.bootstrap.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服务端私有存储及功能门槛配置；HTTP 请求不能修改这些值。 */
@ConfigurationProperties("haizhuo.brain.artifacts.markdown")
public class RunArtifactProperties {
    private boolean enabled;
    private String storageDirectory;
    private int maxBytes = 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStorageDirectory() { return storageDirectory; }
    public void setStorageDirectory(String storageDirectory) { this.storageDirectory = storageDirectory; }
    public int getMaxBytes() { return maxBytes; }
    public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
}
