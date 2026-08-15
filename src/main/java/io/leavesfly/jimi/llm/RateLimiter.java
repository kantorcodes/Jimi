package io.leavesfly.jimi.llm;

import io.leavesfly.jimi.config.info.LLMProviderConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.Queue;

/**
 * LLM 请求限流器
 * 使用滑动窗口算法实现请求频率限制
 * 
 * <p>功能：
 * - 在指定时间窗口内限制请求次数
 * - 超过限制时自动休眠指定时间
 * - 线程安全实现
 * 
 * @author Jimi Team
 */
@Slf4j
public class RateLimiter {
    
    private final long windowMs;      // 时间窗口（毫秒）
    private final int maxRequests;    // 窗口内最大请求数
    private final long sleepMs;       // 超限时的休眠时间（毫秒）
    
    // 请求时间戳队列（滑动窗口）
    private final Queue<Long> requestTimestamps = new LinkedList<>();
    
    /**
     * 构造函数
     * 
     * @param config 限流配置
     */
    public RateLimiter(LLMProviderConfig.RateLimitConfig config) {
        this.windowMs = config.getWindowMs();
        this.maxRequests = config.getMaxRequests();
        this.sleepMs = config.getSleepMs();
        
        log.info("RateLimiter initialized: {}ms window, {} max requests, {}ms sleep", 
                windowMs, maxRequests, sleepMs);
    }
    
    /**
     * 在发送请求前调用，根据限流规则决定是否需要等待
     * 线程安全
     */
    public synchronized void acquirePermit() {
        long now = System.currentTimeMillis();
        
        // 清理窗口外的旧请求记录
        while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() >= windowMs) {
            requestTimestamps.poll();
        }
        
        // 检查是否超过限流
        if (requestTimestamps.size() >= maxRequests) {
            long waitTime = sleepMs;
            
            log.debug("Rate limit exceeded ({} requests in {}ms window), sleeping for {}ms", 
                    maxRequests, windowMs, sleepMs);
            
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limiter sleep interrupted", e);
            }
            
            // 休眠后重新清理窗口
            now = System.currentTimeMillis();
            while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() >= windowMs) {
                requestTimestamps.poll();
            }
        }
        
        // 记录当前请求时间
        requestTimestamps.offer(now);
    }
    
    /**
     * 获取当前窗口内的请求数
     * 
     * @return 当前窗口内的请求计数
     */
    public synchronized int getCurrentRequestCount() {
        long now = System.currentTimeMillis();
        
        // 清理窗口外的旧请求记录
        while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() >= windowMs) {
            requestTimestamps.poll();
        }
        
        return requestTimestamps.size();
    }
    
    /**
     * 重置限流器状态
     */
    public synchronized void reset() {
        requestTimestamps.clear();
        log.debug("RateLimiter reset");
    }
}
