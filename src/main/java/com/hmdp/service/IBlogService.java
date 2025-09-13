package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    Result queryById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result queryHotBlog(Integer current);

    void isLikeBlog(Blog blog);
}
