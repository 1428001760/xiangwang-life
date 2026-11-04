package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
  @Resource
  private StringRedisTemplate stringRedisTemplate;
  @Resource
  private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        if (user ==null){
            return  Result.fail("请先登录！");
        }
        Long userId = user.getId();
        String key = "follows:"+userId;
        if (isFollow){
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            follow.setCreateTime(LocalDateTime.now());
            boolean isSuccess = save(follow);
            if (isSuccess){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            // 取关：删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));

            if (isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }

        }

        return Result.ok();
    }
    @Override
    public Result isFollow(Long followUserId) {
        // 1. 【关键修复】先判空，防止未登录空指针！
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 未登录，直接返回未关注
            return Result.ok(false);
        }
        Long userId = user.getId();
        // 2. 查询是否关注
        Long count = query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        // 3. 判断并返回结果
        return Result.ok(count > 0);
    }
    @Override
    public Result followCommons(Long id) {
        // 补1：登录判空
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }
        Long userId = user.getId();
        String key = "follows:"+userId;
        String key2 = "follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        // 补2：空集合判断
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream().map(item -> BeanUtil.copyProperties(item, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}