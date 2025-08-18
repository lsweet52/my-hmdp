package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    //创建线程池--10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Autowired
    private RedisUtils redisUtils;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 查询商铺
     */
    @Override
    public Result queryById(Long id){
        //缓存穿透
        //Shop shop = redisUtils.queryWithPassThrough(CACHE_SHOP_KEY, id,
                //this::getById, 10L, TimeUnit.SECONDS, Shop.class);

        //缓存击穿-互斥锁
        //Shop shop = queryWithMutex(id);
        //Shop shop = redisUtils.queryWithMutex(CACHE_SHOP_KEY, id,
                //this::getById, 10L, TimeUnit.SECONDS, Shop.class);

        //缓存击穿-逻辑过期
        Shop shop = redisUtils.queryWithLogicalExpire(CACHE_SHOP_KEY, id,
                this::getById, 10L, TimeUnit.SECONDS, Shop.class);
        //Shop shop = queryWithLogicalExpire(id);

        if(shop == null){
            Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 互斥锁解决缓存击穿
     */
    private Shop queryWithMutex(Long id) {
        //1.从redis查询商铺
        String shopkKey = CACHE_SHOP_KEY + id;
        Object cacheShop = redisTemplate.opsForValue().get(shopkKey);

        //2.缓存命中，返回数据
        if (cacheShop != null) {
            if (cacheShop instanceof Shop) {
                return (Shop) cacheShop;
            }

            //如果缓存为空串，返回错误
            if (cacheShop.equals("")) {
                return null;
            }
        }

        //3，缓存未命中，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        String lock = tryLock(lockKey);
        try {
            if (lock != null && !lock.isEmpty()) {
                //3.1. 获取成功，查询数据库，写入redis，释放锁，返回数据
                Shop shop = query().eq("id", id).one();

                //shop为空，写入空值防止缓存穿透
                if (shop == null) {
                    redisTemplate.opsForValue().set(shopkKey, "", 10L, TimeUnit.SECONDS);
                    return null;
                }

                redisTemplate.opsForValue().set(shopkKey, shop, 1440L, TimeUnit.MINUTES);
                return shop;
            }

            //3.2. 获取失败，休眠一段时间，继续查询缓存
            Thread.sleep(100);
            return queryWithMutex(id);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey, lock);
        }
    }

    /**
     *  逻辑过期解决缓存穿透
     */
    private Shop queryWithLogicalExpire(Long id){
        //查询缓存
        String shopKey = CACHE_SHOP_KEY + id;
        RedisData cache = (RedisData)redisTemplate.opsForValue().get(shopKey);

        //缓存未命中，查询数据库，重建缓存，返回数据
        if(cache == null){
            Shop shop = getById(id);

            //缓存空值，防止缓存穿透
            if(shop == null){
                redisTemplate.opsForValue().set(shopKey, "", 10L, TimeUnit.SECONDS);
                return null;
            }
            RedisData redisData = new RedisData();
            redisData.setData(shop);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(3600L));
            redisTemplate.opsForValue().set(shopKey, redisData);
            return shop;
        }

        //防止缓存穿透
        if(cache.getData() != null && ("".equals(cache.getData()))){
            return null;
        }

        //缓存命中，判断是否过期
        LocalDateTime expireTime = cache.getExpireTime();
        Shop shop = (Shop)cache.getData();

        //未过期，返回数据
        if(LocalDateTime.now().isBefore(expireTime)){
            return shop;
        }

        //过期，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        String lock = tryLock(lockKey);

        //未获取锁，返回旧数据
        if(lock == null || lock.isEmpty()){
            return shop;
        }

        //获取到锁，开启新线程，查询数据库，重建缓存，释放锁，返回旧数据
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                this.saveShop2Redis(id, 3600L);
            } catch (Exception e) {
                log.error("异步刷新缓存失败: {}", e);
            } finally {
                unLock(lockKey, lock);
            }
        });
        return shop;
    }

    /**
     * 解决缓存穿透
     */
    public Shop queryWithPassThrough(Long id) {
        //查询缓存
        String key = CACHE_SHOP_KEY + id;
        Object cacheShop = redisTemplate.opsForValue().get(key);

        //有，直接返回
        if (cacheShop != null) {
            if (cacheShop instanceof Shop) {
                return (Shop) cacheShop;
            }

            //如果缓存为空串，返回错误
            if (cacheShop.equals("")) {
                return null;
            }
        }

        //没有，从数据库查询
        Shop shop = getById(id);
        if (shop == null) {
            //缓存空值，防止缓存穿透
            redisTemplate.opsForValue().set(key, "", 1L, TimeUnit.MINUTES);
            return null;
        }

        //将数据写入缓存
        redisTemplate.opsForValue().set(key, shop, 1440L, TimeUnit.MINUTES);

        //返回结果
        return shop;
    }

    /**
     * 修改店铺
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        //1.修改数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺不存在");
        }
        updateById(shop);

        //2.删除缓存
        String key = CACHE_SHOP_KEY + id;
        redisTemplate.delete(key);
        return Result.ok();
    }

    /**
     * 获取锁
     */
    private String tryLock(String key) {
        String lock = UUID.randomUUID().toString();
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, lock, 10L, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag) ? lock : null;
    }


    /**
     * 释放锁
     */
    public void unLock(String key, String lock) {
        String value = (String) redisTemplate.opsForValue().get(key);
        if (value != null && value.equals(lock)) {
            redisTemplate.delete(key);
        }
    }

    /**
     *存储逻辑过期至redis
     */
    public void saveShop2Redis(Long id, Long expire){
        Shop shop = getById(id);
//        try {
//            Thread.sleep(200);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expire));
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, redisData);
    }

}
