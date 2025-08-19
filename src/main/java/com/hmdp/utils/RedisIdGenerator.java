package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdGenerator {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    //开始时间
    private static final long BEGIN_TIME = 1640995200L;

    /**
     * 生成id
     * @param prefix key前缀
     * [1]符号位 -- 0
     * [31] 时间戳
     * [32] 自增
     */
    public long nextId(String prefix){
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long time = nowSeconds - BEGIN_TIME;
        //作为key
        String data = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long count = redisTemplate.opsForValue().increment("icr:" + prefix + ":" + data);
        if(count == null){
            throw new RuntimeException("生成id异常");
        }
        return (time << 32) | (count & 0xFFFFFFFFL);
    }
}
