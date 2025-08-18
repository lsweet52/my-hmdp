package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private static final String LOGIN_TOKEN = "login:token:";

    private RedisTemplate redisTemplate;

    public RefreshTokenInterceptor(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头的token
        String token = request.getHeader("authorization");

        //从redis获取用户
        if(token == null || token.isEmpty()){
            return true;
        }
        String tokenKey = LOGIN_TOKEN + token;
        Map<Object, Object> map = redisTemplate.opsForHash().entries(tokenKey);

        //判断用户是否存在
        if(map.isEmpty()){
            return true;
        }

        //将获得的Hash数据转成对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);

        //存在，保存到ThreadLocal
        UserHolder.saveUser(userDTO);

        //刷新token有效期
        redisTemplate.expire(tokenKey, 60, TimeUnit.MINUTES);

        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
