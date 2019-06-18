package com.atguigu.gmall.order.task;

import com.alibaba.dubbo.config.annotation.Reference;
import com.atguigu.gmall.bean.OrderInfo;
import com.atguigu.gmall.service.OrderService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@EnableScheduling // 开启轮询扫描  关闭订单
@Component
public class OrderTask {
    // 轮询扫描的工具 spring task
    @Reference
    private OrderService orderService;
                     // 分 时 日 月 周 年 ?
    //  0/10 每10 秒扫描一次
    // 5 每分钟的第五秒进行扫描
    @Scheduled(cron = "0/10 * * * * ?")
    public void checkOrder() {
        System.out.println("开始处理过期订单");
        List<OrderInfo> expiredOrderList = orderService.getExpiredOrderList();
        if(expiredOrderList != null && expiredOrderList.size()> 0){
            for (OrderInfo orderInfo : expiredOrderList) {
                orderService.execExpiredOrder(orderInfo);
            }
        }

    }

}
