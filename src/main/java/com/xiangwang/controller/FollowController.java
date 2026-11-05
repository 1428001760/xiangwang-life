package com.xiangwang.controller;
import com.xiangwang.dto.Result;
import com.xiangwang.service.IFollowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.Resource;
@RestController
@RequestMapping("/follow") // 和前端请求的/follow 路径完全匹配！
public class FollowController {

    //  1. 注入Service
    @Resource
    private IFollowService followService;

    //  2. 关注/取关接口（修正语法错误、路径变量、参数）
    @PutMapping("/{id}/{isFollow}") // 路径变量名和@PathVariable对应
    public Result follow(
            @PathVariable("id") Long followUserId,  // 被关注用户ID
            @PathVariable("isFollow") Boolean isFollow) { // 关注/取关标记（true=关注，false=取关）
        // 调用Service方法，返回结果
        return followService.follow(followUserId, isFollow);
    }

    //  3. 判断是否关注接口
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }
    @GetMapping("/common/{id}")
    public  Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }

}