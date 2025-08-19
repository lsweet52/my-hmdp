package com.hmdp.service.impl;

import com.hmdp.dto.Result;
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
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdGenerator redisIdGenerator;

    /**
     * 秒杀卷下单
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断秒杀是否开始或结束
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        if(now.isBefore(beginTime)) {
            //未开始，返回错误
            return Result.fail("秒杀尚未开始");
        }

        if(now.isAfter(endTime)) {
            //已结束，返回错误
            return Result.fail("秒杀已经结束");
        }

        //在秒杀时间内，判断库存是否充足
        Integer stock = voucher.getStock();
        if(stock < 1){
            //不充足，返回错误
            return Result.fail("库存不足");
        }

        synchronized (userId.toString().intern()) {
            //获取代理对象，当前对象直接调用，会导致事务失效--事务基于AOP动态代理实现
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId, Long userId) {
        //根据优惠卷id用户id查询订单
        Integer count = query().eq("voucher_id", voucherId)
                .eq("user_id", userId)
                .count();

        //订单存在，返回错误
        if(count > 0){
            return Result.fail("不能重复下单");
        }

        //库存充足，扣减库存，创建订单
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //乐观锁解决超卖问题
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足");
        }

        long orderId = redisIdGenerator.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setUpdateTime(LocalDateTime.now());
        voucherOrder.setStatus(1);
        save(voucherOrder);

        //返回订单id
        return Result.ok(orderId);
    }
}
