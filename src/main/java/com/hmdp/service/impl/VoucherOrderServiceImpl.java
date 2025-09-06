package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdGenerator;
import com.hmdp.utils.UserHolder;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdGenerator redisIdGenerator;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IVoucherOrderService iVoucherOrderService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private volatile boolean running = true;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    //private static final BlockingQueue<VoucherOrder> orderQueue = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService HANDLE_SECKILL_ORDER = Executors.newSingleThreadExecutor();


    /**
     * 优惠卷秒杀--异步秒杀--redis + mq
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdGenerator.nextId("order");
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("不能重复下单");
        }
        try {
            //执行lua脚本
            Long res = stringRedisTemplate.execute(SECKILL_SCRIPT,
                    Collections.emptyList(),
                    String.valueOf(voucherId),
                    String.valueOf(userId),
                    String.valueOf(orderId));
            int result = res.intValue();

            //判断是否可以购买
            if (result != 0) {
                //不能，返回异常
                return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
            }

            //返回订单id
            return Result.ok(orderId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //库存充足，扣减库存，创建订单
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //乐观锁解决超卖问题
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setUpdateTime(LocalDateTime.now());
        voucherOrder.setStatus(1);
        save(voucherOrder);
    }


    /**
     * 阻塞队列异步完成订单操作
     */
   /* private class handleSecKillOrder implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderQueue.take();
                    proxy.createVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    /**
     * spring初始化后开始异步下单操作
     */
    @PostConstruct
    private void init() {
        HANDLE_SECKILL_ORDER.submit(new handleSecKillOrder());
    }

    /**
     * 应用关闭时设置 running=false
     */
    @PreDestroy
    public void stop() {
        running = false;
        log.info("停止异步下单线程");
    }


    /**
     * 消息队列异步下单
     */
    private class handleSecKillOrder implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (running) {
                try {
                    //获取消息队列的订单
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofMillis(200)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //失败没有消息，继续循环
                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    //解析订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //成功，下单
                    iVoucherOrderService.createVoucherOrder(voucherOrder);

                    //ACK确认
                    redisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    if (running) {
                        log.error("处理订单异常", e);
                        handlePendingList();
                    }
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //获取消息队列的订单
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            //读取pendingList
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    //失败没有消息，pendinglist没有异常消息，结束循环
                    if (list == null || list.isEmpty()) {
                        break;
                    }

                    //解析订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //成功，下单
                    iVoucherOrderService.createVoucherOrder(voucherOrder);

                    //ACK确认
                    redisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }



//    /**
//     * 优惠卷秒杀
//     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始或结束
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        LocalDateTime now = LocalDateTime.now();
//        if(now.isBefore(beginTime)) {
//            //未开始，返回错误
//            return Result.fail("秒杀尚未开始");
//        }
//
//        if(now.isAfter(endTime)) {
//            //已结束，返回错误
//            return Result.fail("秒杀已经结束");
//        }
//
//        //在秒杀时间内，判断库存是否充足
//        Integer stock = voucher.getStock();
//        if(stock < 1){
//            //不充足，返回错误
//            return Result.fail("库存不足");
//        }
//
//        /*//解决一人一单的并发安全问题--单机
//        synchronized (userId.toString().intern()) {
//            //获取代理对象，当前对象直接调用，会导致事务失效--事务基于AOP动态代理实现
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        }
//
//        //集群模式下的一人一单解决--分布式锁
//        boolean success = Ilock.tryLock("order:" + userId, 5L);
//        if(!success){
//            return Result.fail("不能重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        } finally {
//            Ilock.unLock("order:" + userId);
//        }*/
//
//        //使用Redisson的锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("不能重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        } finally {
//            lock.unlock();
//        }
//    }

    }
}
