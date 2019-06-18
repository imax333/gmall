package com.atguigu.gmall.order.mq;

import com.atguigu.gmall.bean.enums.ProcessStatus;
import com.atguigu.gmall.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import javax.jms.JMSException;
import javax.jms.MapMessage;

@Component // 加入spring 容器
public class OrderConsumer {

    @Autowired
    private OrderService orderService;

    @JmsListener(destination = "PAYMENT_RESULT_QUEUE",containerFactory = "jmsQueueListener")
    public void consumerPaymentResult(MapMessage mapMessage) throws JMSException {

        String orderId = mapMessage.getString("orderId");
        String result = mapMessage.getString("result");
        if("success".equals(result)){
            // 更新订单信息
            orderService.updateOrderStatus(orderId, ProcessStatus.PAID);
            // 通知 库存模块 减库存
            orderService.sendOrderStatus(orderId);
            // 更新数据
            orderService.updateOrderStatus(orderId, ProcessStatus.NOTIFIED_WARE);

        }
    }

    @JmsListener(destination = "SKU_DEDUCT_QUEUE",containerFactory = "jmsQueueListener")
    public void consumeSkuDeduct(MapMessage mapMessage) throws JMSException {
        String orderId = mapMessage.getString("orderId");
        String status = mapMessage.getString("status");
        if("DEDUCTED".equals(status)){
            orderService.updateOrderStatus(orderId,ProcessStatus.DELEVERED);
        }else{
            orderService.updateOrderStatus(orderId,ProcessStatus.STOCK_EXCEPTION);
        }
    }
}
