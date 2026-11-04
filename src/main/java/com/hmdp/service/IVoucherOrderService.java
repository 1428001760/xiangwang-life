package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {
    Result seckillVoucher(Long voucherId);
    Result createVoucherOrder(Long voucherId);
    // 加上老师的新方法声明！不然proxy调用会报错
    void createVoucherOrder(VoucherOrder voucherOrder);
    // MQ 消费者调用的落库入口（加分布式锁 + 事务）
    void handleVoucherOrder(VoucherOrder voucherOrder);
}