package com.hmdp.service.impl;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeListWithCache() {
        String key = CACHE_SHOP_TYPE_KEY;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
         List<ShopType>  shopTypes =  JSONUtil.toList(json, ShopType.class);
            return Result.ok(shopTypes);
        }
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if (shopTypes.isEmpty()) {
            return Result.fail("店铺类型不存在");
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypes),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return Result.ok(shopTypes);

    }
}