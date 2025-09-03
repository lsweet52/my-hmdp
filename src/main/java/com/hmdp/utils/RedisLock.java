package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/*Redis实现分布式锁，实现集群模式下的超卖问题*/

@Component
public class RedisLock implements ILock{

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final ThreadLocal<String> LOCK_VALUE = new ThreadLocal<>();

    @Override
    public boolean tryLock(String name, Long timeout) {
        String key = LOCK_KEY_PREFIX + name;
        String value = UUID.randomUUID().toString().replace("-", "") + Thread.currentThread().getId();
        LOCK_VALUE.set(value);
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, value, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock(String name) {
        String key = LOCK_KEY_PREFIX + name;
        String value = LOCK_VALUE.get();
        if(value.equals(redisTemplate.opsForValue().get(key))){
            redisTemplate.delete(key);
        }
        LOCK_VALUE.remove();
    }
}
