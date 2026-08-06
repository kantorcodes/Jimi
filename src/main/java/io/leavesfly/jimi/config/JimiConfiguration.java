package io.leavesfly.jimi.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.leavesfly.jimi.config.info.*;
import io.leavesfly.jimi.core.compaction.Compaction;
import io.leavesfly.jimi.core.compaction.SimpleCompaction;

import io.leavesfly.jimi.core.sandbox.SandboxValidator;
import io.leavesfly.jimi.exception.ConfigException;
import io.leavesfly.jimi.memory.MemoryConsolidator;
import io.leavesfly.jimi.memory.MemoryManager;
import io.leavesfly.jimi.wire.Wire;
import io.leavesfly.jimi.wire.WireImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Jimi 应用配置类
 * 统一管理核心 Bean 的创建和配置
 */
@Slf4j
@Configuration
public class JimiConfiguration {

    /**
     * ObjectMapper Bean - JSON 序列化/反序列化
     * 全局单例,用于所有 JSON 处理
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 注册 JavaTimeModule 以支持 Java 8 时间类型
        mapper.registerModule(new JavaTimeModule());

        // 禁用将日期写为时间戳
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 忽略未知属性（提高容错性）
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
    
    /**
     * Wire Bean - 消息总线
     * 全局单例，用于 Engine 内部消息传递
     * 使用 share() 确保所有订阅者共享同一个流，避免重复消息
     */
    @Bean
    public Wire wire() {
        return new WireImpl();
    }
    
    /**
     * Compaction Bean - 上下文压缩策略
     * 全局单例，提供默认的 SimpleCompaction 实现
     */
    @Bean
    public Compaction compaction() {
        return new SimpleCompaction();
    }
    

    /**
     * YAML ObjectMapper Bean - YAML 序列化/反序列化
     * 用于配置文件和 Agent 规范的读取
     */
    @Bean("yamlObjectMapper")
    public ObjectMapper yamlObjectMapper() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        // 注册 JavaTimeModule
        mapper.registerModule(new JavaTimeModule());

        // 忽略未知属性
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }

    /**
     * JimiConfig Bean - 全局配置单例
     * 在应用启动时加载配置
     */
    @Bean
    public JimiConfig jimiConfig(ObjectMapper objectMapper) {
        return ConfigLoaderHelper.loadConfig(objectMapper, null);
    }

    /**
     * 子配置 Bean —— 从 JimiConfig 中提取，供其他 Bean 注入使用
     */
    @Bean
    public MetaToolConfig metaToolConfig(JimiConfig jimiConfig) { return jimiConfig.getMetaTool(); }

    @Bean
    public MemoryConfig memoryConfig(JimiConfig jimiConfig) { return jimiConfig.getMemory(); }

    @Bean
    public MemoryManager memoryManager(MemoryConfig memoryConfig) {
        return new MemoryManager(memoryConfig);
    }

    @Bean
    public MemoryConsolidator memoryConsolidator(MemoryManager memoryManager) {
        return new MemoryConsolidator(memoryManager);
    }

    @Bean
    public SandboxValidator sandboxValidator(JimiConfig jimiConfig) {
        return new SandboxValidator(jimiConfig.getSandbox(), null);
    }

    @Bean
    public LoopEngineeringConfig loopEngineeringConfig(JimiConfig jimiConfig) {
        return jimiConfig.getLoopEngineering();
    }

    @Bean
    public RefineConfig refineConfig(JimiConfig jimiConfig) {
        return jimiConfig.getRefine();
    }

    @Bean
    public SubagentConfig subagentConfig(JimiConfig jimiConfig) {
        return jimiConfig.getSubagent();
    }


    // ==================== 配置加载内部工具类 ====================

    /**
     * 配置加载工具类
     * 负责从配置文件加载、保存和管理 Jimi 配置
     * 使用 YAML 格式
     */
    private static class ConfigLoaderHelper {

        private static final String RESOURCE_CONFIG_PATH = ".jimi/config.yml";

        /**
         * 获取配置文件路径
         */
        public static Path getConfigFilePath() {
            String userHome = System.getProperty("user.home");
            return Paths.get(userHome, RESOURCE_CONFIG_PATH);
        }

        /**
         * 加载配置
         * 配置优先级：自定义配置文件 > 默认配置文件 > 内置默认配置
         * 使用 YAML 格式
         */
        public static JimiConfig loadConfig(ObjectMapper objectMapper, Path customConfigFile) {
            Path configFile = customConfigFile != null ? customConfigFile : getConfigFilePath();

            JimiConfig config;
            if (Files.exists(configFile)) {
                log.debug("Loading config from file: {}", configFile);
                try {
                    // 使用 YAML ObjectMapper
                    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                    yamlMapper.registerModule(new JavaTimeModule());
                    yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    config = yamlMapper.readValue(Files.newInputStream(configFile), JimiConfig.class);
                } catch (IOException e) {
                    throw new ConfigException("Failed to load config from file: " + configFile, e);
                }
            } else {
                log.debug("No config file found, creating default config");
                config = getDefaultConfig();
            }

            // 验证配置
            try {
                config.validate();
            } catch (IllegalStateException e) {
                throw new ConfigException("Invalid configuration: " + e.getMessage(), e);
            }

            return config;
        }

        /**
         * 保存配置
         */
        public static void saveConfig(JimiConfig config, Path configFile) {
            try {
                // 确保目录存在
                Files.createDirectories(configFile.getParent());

                // 使用 YAML ObjectMapper
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                yamlMapper.registerModule(new JavaTimeModule());
                yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                yamlMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(Files.newOutputStream(configFile), config);

                log.info("Config saved to: {}", configFile);
            } catch (IOException e) {
                throw new ConfigException("Failed to save config to file: " + configFile, e);
            }
        }

        /**
         * 获取默认内置配置
         * 从 resources/.jimi/config.yml 加载
         */
        public static JimiConfig getDefaultConfig() {
            try {
                URL resource = ConfigLoaderHelper.class.getClassLoader().getResource(RESOURCE_CONFIG_PATH);
                if (resource != null) {
                    log.debug("Loading default config from classpath: {}", RESOURCE_CONFIG_PATH);
                    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                    yamlMapper.registerModule(new JavaTimeModule());
                    yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    return yamlMapper.readValue(resource, JimiConfig.class);
                }
            } catch (IOException e) {
                log.warn("Failed to load default config from classpath: {}", e.getMessage());
            }

            throw new ConfigException("Failed to load default config from classpath: " + RESOURCE_CONFIG_PATH);
        }
    }

    /**
     * 公共方法：加载配置（用于测试）
     */
    public static JimiConfig loadConfig(ObjectMapper objectMapper, Path customConfigFile) {
        return ConfigLoaderHelper.loadConfig(objectMapper, customConfigFile);
    }
}
