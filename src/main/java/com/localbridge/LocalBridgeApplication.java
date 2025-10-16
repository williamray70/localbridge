// ============================================================================
// Main Application Entry Point
// ============================================================================

package com.localbridge;

import com.localbridge.engine.ChannelConfig;
import com.localbridge.engine.ChannelEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

public class LocalBridgeApplication {
    private static final Logger log = LoggerFactory.getLogger(LocalBridgeApplication.class);
    
    private final Map<String, ChannelEngine> channels = new HashMap<>();
    
    public static void main(String[] args) throws Exception {
        LocalBridgeApplication app = new LocalBridgeApplication();
        
        // Load channel configurations
        Path configDir = Paths.get("conf/channels");
        app.loadChannels(configDir);
        
        // Start all enabled channels
        app.startAll();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(app::stopAll));
        
        log.info("LocalBridge HL7 Channel Engine started");
        
        // Keep running
        Thread.currentThread().join();
    }
    
    public void loadChannels(Path configDir) throws Exception {
        Yaml yaml = new Yaml();
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.yaml")) {
            for (Path configFile : stream) {
                log.info("Loading channel config: {}", configFile.getFileName());
                
                try (InputStream input = new FileInputStream(configFile.toFile())) {
                    Map<String, Object> data = yaml.load(input);
                    ChannelConfig config = parseChannelConfig(data);
                    
                    if (config.isEnabled()) {
                        ChannelEngine engine = new ChannelEngine(config);
                        channels.put(config.getName(), engine);
                        log.info("Loaded channel: {}", config.getName());
                    } else {
                        log.info("Channel disabled: {}", config.getName());
                    }
                } catch (Exception e) {
                    log.error("Failed to load channel config: {}", configFile, e);
                }
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private ChannelConfig parseChannelConfig(Map<String, Object> data) {
        Map<String, Object> channelData = (Map<String, Object>) data.get("channel");
        ChannelConfig config = new ChannelConfig();
        
        config.setName((String) channelData.get("name"));
        config.setEnabled((Boolean) channelData.getOrDefault("enabled", true));
        config.setInputDir((String) channelData.get("input-dir"));
        config.setOutputDir((String) channelData.get("output-dir"));
        config.setErrorDir((String) channelData.get("error-dir"));
        config.setArchiveDir((String) channelData.get("archive-dir"));
        
        if (channelData.containsKey("input-pattern")) {
            config.setInputPattern((String) channelData.get("input-pattern"));
        }
        
        if (channelData.containsKey("poll-interval-ms")) {
            config.setPollIntervalMs(((Number) channelData.get("poll-interval-ms")).longValue());
        }
        
        if (channelData.containsKey("batch-size")) {
            config.setBatchSize((Integer) channelData.get("batch-size"));
        }
        
        // Transformer config
        Map<String, Object> transformerData = 
            (Map<String, Object>) channelData.get("transformer");
        config.setTransformerType((String) transformerData.get("type"));
        
        if (transformerData.containsKey("script")) {
            config.setTransformerScript((String) transformerData.get("script"));
        }
        if (transformerData.containsKey("class")) {
            config.setTransformerClass((String) transformerData.get("class"));
        }
        if (transformerData.containsKey("create-missing")) {
            config.setCreateMissing((Boolean) transformerData.get("create-missing"));
        }
        
        config.setTransformerConfig(transformerData);
        
        // Archive config
        if (channelData.containsKey("archive")) {
            Map<String, Object> archiveData = 
                (Map<String, Object>) channelData.get("archive");
            config.setArchiveEnabled((Boolean) archiveData.getOrDefault("enabled", true));
            config.setArchiveCompress((Boolean) archiveData.getOrDefault("compress", true));
        }
        
        return config;
    }
    
    public void startAll() {
        for (ChannelEngine engine : channels.values()) {
            engine.start();
        }
    }
    
    public void stopAll() {
        log.info("Shutting down all channels...");
        for (ChannelEngine engine : channels.values()) {
            engine.stop();
        }
    }
}
