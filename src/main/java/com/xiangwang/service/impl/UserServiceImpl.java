package com.xiangwang.service.impl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiangwang.dto.LoginFormDTO;
import com.xiangwang.dto.Result;
import com.xiangwang.dto.UserDTO;
import com.xiangwang.entity.User;
import com.xiangwang.mapper.UserMapper;
import com.xiangwang.service.IUserService;
import com.xiangwang.utils.RegexUtils;
import com.xiangwang.utils.SystemConstants;
import com.xiangwang.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static com.xiangwang.utils.RedisConstants.*;
/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 2. 生成6位验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码到redis当中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4. 模拟发送验证码
        log.info("发送验证码成功，手机号：{}，验证码：{}", phone, code);
        return Result.ok();
    }
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 2. 校验验证码(从redis里面取)
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            // 3. 不一致，报错
            return Result.fail("验证码错误");
        }
        // 4. 一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 5. 判断用户是否存在
        if (user == null) {
            // 6. 不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 7. 保存用户信息到redis中
        //7.1随机生成token 作为登录令牌
        String token = UUID.randomUUID(true).toString();
        //7.2将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        //7.3存储
        String tokenKey = LOGIN_USER_KEY+token ;
        stringRedisTemplate.opsForHash().putAll( tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //8返还token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        // 用SystemConstants里的前缀，规范写法
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
         String keySuddix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
         String key = USER_SIGN_KEY+userId+keySuddix;
        int dayOfMonth = now.getDayOfMonth();

        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);



        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuddix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuddix;
        int dayOfMonth = now.getDayOfMonth();//今天（比如22号，对应offset 21）

        // -------------------------- 1. 获取本月到今天为止的所有签到数据（对应PPT问题2） --------------------------
        // 命令：BITFIELD key GET u[dayOfMonth] 0
        // 含义：从offset 0开始，读取dayOfMonth个bit，组成无符号数（1号到今天的签到状态）
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        // 读取dayOfMonth个bit，作为无符号数
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        // 从offset 0开始读
                        .valueAt(0)
        );

        // 处理空结果（用户本月从未签到过）
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }

        // 拿到签到数据的十进制数字
        long number = result.get(0);
        int count = 0;

        // -------------------------- 2. 从后向前遍历bit位（对应PPT问题3） --------------------------
        while (true) {
            // 让数字跟1做与运算，得到最后一个bit位（判断是否签到）
            long bit = number & 1;

            // 判断这个bit位是否为0
            if (bit == 0) {
                // 如果是0，说明遇到未签到，结束循环
                break;
            }

            // 如果不是0，说明已签到，计数器加一
            count++;

            // 数字无符号右移一位，抛弃最后一个bit位，继续下一个bit位
            number = number >>> 1;
        }
        // 返回连续签到天数
        return Result.ok(count);
    }
}