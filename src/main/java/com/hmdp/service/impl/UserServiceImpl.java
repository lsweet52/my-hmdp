package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final String LOGIN_CODE = "login:code:";
    private static final String LOGIN_TOKEN = "login:token:";

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合，返回错误
            return Result.fail("手机格式错误");
        }

        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //将验证码保存到redis中
        redisTemplate.opsForValue().set(LOGIN_CODE + phone, code, 5, TimeUnit.MINUTES);

        //发送验证码
        log.info("发送验证码: {}", code);

        //返回成功结果
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合，返回错误
            return Result.fail("手机号格式错误");
        }

        //从redis获取验证码并校验验证码
        String code = loginForm.getCode();
        String cacheCode = (String) redisTemplate.opsForValue().get(LOGIN_CODE + phone);
        if(!code.equals(cacheCode)){
            //不一致，返回错误信息
            return Result.fail("验证码错误");
        }

        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //判断用户是否存在
        if (user == null) {
            //用户不存在，创建用户，保存至数据库
            user = createUserWithPhone(phone);
        }

        //保存用户到redis中,以随机token(UUID)为key
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);

        //将对象转为Hash存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        String tokenKey = LOGIN_TOKEN + token;
        redisTemplate.opsForHash().putAll(tokenKey, userMap);

        //设置token有效期
        redisTemplate.expire(tokenKey, 60, TimeUnit.MINUTES);

        //返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
