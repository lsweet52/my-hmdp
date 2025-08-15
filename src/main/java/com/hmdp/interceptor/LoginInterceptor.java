package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession();

        //获取用户
        UserDTO userDTO = (UserDTO)session.getAttribute("user");

        //判断用户是否存在
        if(userDTO == null){
            //不存在，拦截，返回401
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        //存在，保存到ThreadLocal
        UserHolder.saveUser(userDTO);

        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

    }
}
