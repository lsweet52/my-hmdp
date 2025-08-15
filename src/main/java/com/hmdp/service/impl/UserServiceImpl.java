package com.hmdp.service.impl;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合，返回错误
            return Result.fail("手机格式错误");
        }

        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //将验证码保存到session中
        session.setAttribute("code", code);

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

        //校验验证码
        String code = loginForm.getCode();
        String sessionCode = (String)session.getAttribute("code");
        if(!code.equals(sessionCode)){
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

        //保存用户到session中
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        session.setAttribute("user", userDTO);

        //返回成功结果
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
