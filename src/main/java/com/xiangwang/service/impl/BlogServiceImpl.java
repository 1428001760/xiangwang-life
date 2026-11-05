package com.xiangwang.service.impl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiangwang.dto.Result;
import com.xiangwang.dto.ScrollResult;
import com.xiangwang.dto.UserDTO;
import com.xiangwang.entity.Blog;
import com.xiangwang.entity.Follow;
import com.xiangwang.entity.User;
import com.xiangwang.mapper.BlogMapper;
import com.xiangwang.mapper.UserInfoMapper;
import com.xiangwang.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiangwang.service.IFollowService;
import com.xiangwang.service.IUserService;
import com.xiangwang.utils.SystemConstants;
import com.xiangwang.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static com.xiangwang.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.xiangwang.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    private UserInfoMapper userInfoMapper;

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();

        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("该笔记不存在!");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;

        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;

        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        String idStr = StrUtil.join(",", ids);

        List<User> users = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();

        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());

        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败");
        }

        // 查询关注当前博主的所有粉丝
        List<Follow> fans = followService.query()
                .eq("follow_user_id", user.getId())
                .list();


        for (Follow fan : fans) {
            // 拼接：feed: + 粉丝ID
            String feedKey = FEED_KEY + fan.getUserId();
            stringRedisTemplate.opsForZSet().add(
                    feedKey,
                    blog.getId().toString(),
                    System.currentTimeMillis()
            );
        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogFollow(Long max, Integer offset) {
        // ====================== 1. 获取当前登录用户 ======================
        Long userId = UserHolder.getUser().getId();
        // 构建当前用户的收件箱key：feed:user:用户ID
        String key = FEED_KEY + userId;

        // ====================== 2. 从Redis ZSet分页查询笔记 ======================
        // reverseRangeByScoreWithScores：按分数倒序查询，同时返回分数（时间戳）
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(
                        key,        // Redis的key（当前用户的收件箱）
                        0,          // 最小分数：0（笔记时间戳都是正的，所以从0开始）
                        max == null ? Double.MAX_VALUE : max, // 最大分数：第一次查询为null，取所有数据
                        offset,     // 偏移量：处理同一时间戳的笔记
                        2           // 每次查询的数量（演示用2条，实际可改成5/10）
                );

        // ====================== 3. 空结果判断（没有更多笔记了） ======================
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(); // 返回空，前端停止加载
        }

        // ====================== 4. 解析数据：笔记ID、最小时间戳、偏移量 ======================
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 本次查询的最小时间戳（给下一次请求用）本次查询到的「最后一条数据的分数」
        int os = 1;       // 下一次请求的偏移量（处理同一时间戳的笔记）

        // 循环解析ZSet返回的数据
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1 提取笔记ID（ZSet的value存的是笔记ID）
            blogIds.add(Long.valueOf(tuple.getValue()));
            // 4.2 提取笔记的创建时间戳（ZSet的score存的是创建时间）
            long time = tuple.getScore().longValue();

            // 4.3 处理同一时间戳的笔记（避免重复加载）
            if (time == minTime) {
                // 如果当前笔记时间戳和上一个一样，偏移量+1
                os++;
            } else {
                // 时间戳不一样，更新最小时间戳，重置偏移量为1
                minTime = time;
                os = 1;
            }
        }

        // ====================== 5. 批量查询笔记详情（关键：强制按Redis顺序排序） ======================
        // 拼接笔记ID，用逗号分隔
        String idStr = StrUtil.join(",", blogIds);
        // MyBatis-Plus查询，用ORDER BY FIELD强制按Redis的顺序返回
        List<Blog> blogs = query()
                .in("id", blogIds)
                .last("ORDER BY FIELD(id," + idStr + ")") // 重点！！按Redis的顺序排序
                .list();

        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        // ====================== 6. 封装分页结果，返回给前端 ======================
        ScrollResult result = new ScrollResult();
        result.setList(blogs);      // 本次查询的笔记列表
        result.setMinTime(minTime); // 下一次请求的max参数
        result.setOffset(os);       // 下一次请求的offset参数


        return Result.ok(result);
    }
}