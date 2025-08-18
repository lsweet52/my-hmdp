package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * 缓存封装类
 */
@Component
@Slf4j
public class RedisUtils {

    //线程池，用于重建缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 数据写入redis
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        redisTemplate.opsForValue().set(key, value, time, timeUnit);
    }

    /**
     * 逻辑过期写入redis
     */
    public void setWithLogicalExpire(String key, Object value, Long expire, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)));
        redisData.setData(value);
        redisTemplate.opsForValue().set(key, redisData);
    }

    /**
     * 缓存穿透
     * @param prefix 缓存key前缀
     * @param id 查询id
     * @param dbFallBack 数据库查询函数
     * @param time 缓存有效期
     * @param timeUnit 缓存有效期时间单位
     * @param <R> 返回数据类型
     * @param <ID> 查询id类型
     */
    public <R, ID> R queryWithPassThrough(String prefix, ID id, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit, Class<R> clazz){
        //查询缓存
        String key = prefix + id;
        Object cache = redisTemplate.opsForValue().get(key);

        //缓存命中
        if (cache != null) {

            //缓存为空值，返回空
            if("".equals(cache)){
                return null;
            }

            //返回缓存数据
            return clazz.cast(cache);
        }

        //缓存未命中，查询数据库
        R r = dbFallBack.apply(id);

        //查询为空，写入空串,返回空
        if(r == null){
            redisTemplate.opsForValue().set(key, "", 1L, TimeUnit.MINUTES);
            return null;
        }

        //不为空，数据写入缓存
        redisTemplate.opsForValue().set(key, r, time, timeUnit);

        //返回数据
        return r;
    }

    /**
     * 互斥锁解决缓存击穿
     */
    public <R, ID> R queryWithMutex(String prefix,
                                    ID id,
                                    Function<ID, R> dbFallBack,
                                    Long time,
                                    TimeUnit timeUnit,
                                    Class<R> clazz){
        //查询缓存
        String key = prefix + id;
        Object cache = redisTemplate.opsForValue().get(key);

        //缓存为空，尝试获取锁
        if(cache == null){
            String lockKey = LOCK_SHOP_KEY + id;
            String lock = tryLock(lockKey);
            try {
                //未获取到锁，休眠一会，重新查询缓存，返回数据
                if(lock == null){
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                //获取到锁，查询数据库，写入缓存，返回数据,释放锁
                R r = dbFallBack.apply(id);
                if(r == null) {
                    redisTemplate.opsForValue().set(key, "", 1L, TimeUnit.MINUTES);
                    return null;
                }
                redisTemplate.opsForValue().set(key, r, time, timeUnit);
                return r;
            } catch (RuntimeException e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey, lock);
            }
        }

        //缓存不为空，返回数据
        return clazz.cast(cache);
    }

    /**
     * 缓存击穿--逻辑过期
     */
    public <R, ID> R queryWithLogicalExpire(String prefix,
                                            ID id, Function<ID, R> dbFallBack,
                                            Long time, TimeUnit timeUnit,
                                            Class<R> clazz){
        //查询缓存
        String key = prefix + id;
        RedisData cache = (RedisData)redisTemplate.opsForValue().get(key);

        //缓存未命中，查询数据库，写入缓存，返回数据
        if(cache == null){
            R r = dbFallBack.apply(id);
            if(r == null){
                redisTemplate.opsForValue().set(key, "", 1L, TimeUnit.MINUTES);
                return null;
            }
            RedisData redisData = new RedisData();
            redisData.setData(r);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
            redisTemplate.opsForValue().set(key, redisData);
            return r;
        }

        //缓存命中，判断是否过期
        LocalDateTime expireTime = cache.getExpireTime();
        R r = clazz.cast(cache.getData());

        //未过期，返回数据
        if(LocalDateTime.now().isBefore(expireTime)){
            return r;
        }

        //过期，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        String lock = tryLock(lockKey);

        try {
            //未索取锁，返回旧数据
            if(lock == null || lock.isEmpty()){
                return r;
            }

            //获取锁，开启新线程，查询数据库，重建缓存，释放锁，返回旧数据
            CACHE_REBUILD_EXECUTOR.submit(()->{
                R r1 = dbFallBack.apply(id);
                if(r1 == null){
                    redisTemplate.opsForValue().set(key, "", 1L, TimeUnit.MINUTES);
                }
                RedisData redisData = new RedisData();
                redisData.setData(r1);
                redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
                redisTemplate.opsForValue().set(key, redisData);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey, lock);
        }
        return r;
    }

    /**
     * 尝试获取锁
     */
    private String tryLock(String key){
        String lock = UUID.randomUUID().toString();
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, lock, 10L, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag) ? lock : null;
    }

    /**
     * 释放锁
     */
    private void unLock(String key, String lock){
        if(lock != null && lock.equals(redisTemplate.opsForValue().get(key))){
            redisTemplate.delete(key);
        }
    }
}
