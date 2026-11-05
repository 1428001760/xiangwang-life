package com.xiangwang.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiangwang.dto.Result;
import com.xiangwang.entity.Voucher;
import com.xiangwang.mapper.VoucherMapper;
import com.xiangwang.entity.SeckillVoucher;
import com.xiangwang.service.ISeckillVoucherService;
import com.xiangwang.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;
import java.util.List;
import static com.xiangwang.utils.RedisConstants.SECKILL_STOCK_KEY;
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 秒杀订单用户集合KEY（和库存区分开）
    private static final String SECKILL_ORDER_KEY = "seckill:order:";
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 1. 保存数据到MySQL
        save(voucher);
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 2. 保存数据到Redis（库存+用户集合）
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
        stringRedisTemplate.opsForSet().add(SECKILL_ORDER_KEY + voucher.getId(), "");
    }
}