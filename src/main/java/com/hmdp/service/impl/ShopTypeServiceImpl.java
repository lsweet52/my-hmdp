package com.hmdp.service.impl;

import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public List<ShopType> queryList() {
        //查询缓存
        String key = "cache:shopType";
        List<ShopType> cacheTypeList = (List<ShopType>) redisTemplate.opsForValue().get(key);

        //缓存有，直接返回
        if(cacheTypeList != null && !cacheTypeList.isEmpty()){
            return cacheTypeList;
        }

        //没有，从数据库查询
        List<ShopType> typeList = list();

        //写入缓存
        if(typeList != null && !typeList.isEmpty()){
            redisTemplate.opsForValue().set(key, typeList);
        }

        //返回数据
        return typeList;
    }
}
