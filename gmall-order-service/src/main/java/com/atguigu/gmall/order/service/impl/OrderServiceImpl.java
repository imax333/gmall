package com.atguigu.gmall.order.service.impl;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.atguigu.gmall.HttpClientUtil;
import com.atguigu.gmall.bean.OrderDetail;
import com.atguigu.gmall.bean.OrderInfo;
import com.atguigu.gmall.bean.enums.OrderStatus;
import com.atguigu.gmall.bean.enums.ProcessStatus;
import com.atguigu.gmall.config.ActiveMQUtil;
import com.atguigu.gmall.config.RedisUtil;
import com.atguigu.gmall.order.mapper.OrderDetailMapper;
import com.atguigu.gmall.order.mapper.OrderInfoMapper;
import com.atguigu.gmall.service.OrderService;
import com.atguigu.gmall.service.PaymentService;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import redis.clients.jedis.Jedis;
import tk.mybatis.mapper.entity.Example;

import javax.jms.*;
import javax.jms.Queue;
import java.util.*;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderInfoMapper orderInfoMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private ActiveMQUtil activeMQUtil;
    @Reference
    private PaymentService paymentService;


    // 返回orderId，保存完，应该调到支付，根据orderId。
    @Override
    public String saveOrder(OrderInfo orderInfo) {
        // 设置订单创建时间
        orderInfo.setCreateTime(new Date());
        // 订单过期时间
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, 1);
        orderInfo.setExpireTime(calendar.getTime());
        // 生成第三方支付编号 可以保证支付的幂等性 只有一个支付结果  只能支付一次
        String outTradeNo = "ATGUIGU" + System.currentTimeMillis() + "" + new Random().nextInt(1000);
        orderInfo.setOutTradeNo(outTradeNo);
        orderInfoMapper.insertSelective(orderInfo);

        List<OrderDetail> orderDetailList = orderInfo.getOrderDetailList();
        for (OrderDetail orderDetail : orderDetailList) {
            orderDetail.setOrderId(orderInfo.getId());
            orderDetailMapper.insertSelective(orderDetail);
        }
        return orderInfo.getId();
    }

    // 生成流水号
    @Override
    public String getTradeNo(String userId) {
        Jedis jdedis = redisUtil.getJdedis();
        String tradeNoKey = "user:" + userId + ":tradeCode";
        String tradeCode = UUID.randomUUID().toString();
        jdedis.set(tradeNoKey, tradeCode);
        jdedis.close();
        return tradeCode;
    }

    // 验证流水号
    @Override
    public boolean checkTradeCode(String userId, String tradeCodeNo) {
        String tradeNoKey = "user:" + userId + ":tradeCode";
        Jedis jdedis = redisUtil.getJdedis();
        String tradeCodeString = jdedis.get(tradeNoKey);
        jdedis.close();
        if (tradeCodeString != null && tradeCodeString.equals(tradeCodeNo)) {
            return true;
        }
        return false;
    }

    // 删除流水号
    @Override
    public void delTradeCode(String userId) {
        String tradeNoKey = "user:" + userId + ":tradeCode";
        Jedis jdedis = redisUtil.getJdedis();
        jdedis.del(tradeNoKey);
        jdedis.close();

    }

    @Override
    public boolean checkStock(String skuId, Integer skuNum) {

        String result = HttpClientUtil.doGet("http://www.gware.com/hasStock?skuId=" + skuId + "&num=" + skuNum);
        if ("1".equals(result)) {
            return true;
        }
        return false;
    }

    @Override
    public OrderInfo getOrderInfoByOrderId(String orderId) {
        OrderInfo orderInfo = orderInfoMapper.selectByPrimaryKey(orderId);
        OrderDetail orderDetail = new OrderDetail();
        orderDetail.setOrderId(orderId);
        // 查询  orderDetails  并赋值
        List<OrderDetail> orderDetails = orderDetailMapper.select(orderDetail);
        orderInfo.setOrderDetailList(orderDetails);
        return orderInfo;
    }

    @Override
    public void updateOrderStatus(String orderId, ProcessStatus processStatus) {
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setId(orderId);
        // 设置订单进程状态
        orderInfo.setProcessStatus(processStatus);
        // 设置订单状态
        orderInfo.setOrderStatus(processStatus.getOrderStatus());
        // 更新订单信息
        orderInfoMapper.updateByPrimaryKeySelective(orderInfo);


    }

    @Override
    public void sendOrderStatus(String orderId) {
        Connection connection = activeMQUtil.getConnection();
        // 因为文档中 需要的参数为json数据
        String jsonString = initWareOrder(orderId);
        try {
            connection.start();
            // 事务控制
            Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue order_result_queue = session.createQueue("ORDER_RESULT_QUEUE");
            //
            MessageProducer producer = session.createProducer(order_result_queue);
            ActiveMQTextMessage activeMQTextMessage = new ActiveMQTextMessage();
            activeMQTextMessage.setText(jsonString);
            producer.send(activeMQTextMessage);
            // 提交事务
            session.commit();
            // 关闭连接
            producer.close();
            session.close();
            connection.close();
        } catch (JMSException e) {
            e.printStackTrace();
        }

    }

    @Override
    public List<OrderInfo> getExpiredOrderList() {
        Example example = new Example(OrderInfo.class);
        // 封装查询条件
        example.createCriteria().andEqualTo("orderStatus", OrderStatus.UNPAID)
                .andLessThan("expireTime",new Date());
        return orderInfoMapper.selectByExample(example);
    }

    // 处理过期订单
    @Async // 异步多线程处理订单
    @Override
    public void execExpiredOrder(OrderInfo orderInfo) {
        // 关闭订单状态
        updateOrderStatus(orderInfo.getId(),ProcessStatus.CLOSED);
        // 关闭交易记录状态
        paymentService.closePayment(orderInfo.getId());
    }

    private String initWareOrder(String orderId) {
        OrderInfo orderInfo = getOrderInfoByOrderId(orderId);
        Map map = initWareOrder(orderInfo);
        return JSON.toJSONString(map);


    }

    // 因为文档中的要求是把数据封装一个map  转化为json
    @Override
    public Map initWareOrder(OrderInfo orderInfo) {

        Map<String, Object> map = new HashMap<>();
        map.put("orderId", orderInfo.getId());
        map.put("consignee", orderInfo.getConsignee());
        map.put("consigneeTel", orderInfo.getConsigneeTel());
        map.put("orderComment", orderInfo.getOrderComment());
        map.put("orderBody", "这是个测试信息");
        map.put("deliveryAddress", orderInfo.getDeliveryAddress());
        map.put("paymentWay", "2");
        map.put("wareId",orderInfo.getWareId());  // 存储库存Id

        // 建立集合  封装 orderDetail数据
        List<Map> detailList = new ArrayList();
        List<OrderDetail> orderDetailList = orderInfo.getOrderDetailList();
        if(orderDetailList != null && orderDetailList.size() > 0){
            for (OrderDetail orderDetail : orderDetailList) {
                HashMap<String, Object> map1 = new HashMap<>();
                map1.put("skuId",orderDetail.getSkuId());
                map1.put("skuNum",orderDetail.getSkuNum());
                map1.put("skuName",orderDetail.getSkuName());
                detailList.add(map1);
            }
        }
        map.put("details",detailList);
        return map;
    }

    @Override
    public List<OrderInfo> splitOrder(String orderId, String wareSkuMap) {
        List<OrderInfo> subOrderInfoList = new ArrayList<>();

        // 获取原始订单
        OrderInfo orderInfoOrigin  = getOrderInfoByOrderId(orderId);
        List<Map> maps  = JSON.parseArray(wareSkuMap, Map.class);
        for (Map map : maps) {
            // 获取仓库编号
            String wareId = (String) map.get("wareId");
            List<String> skuIds = (List<String>) map.get("skuIds");
            // 4 生成订单主表，从原始订单复制，新的订单号，父订单
            // 封装子订单详情集合数据
            OrderInfo subOrderInfo = new OrderInfo();
            // 大部分参数  进行拷贝
            BeanUtils.copyProperties(orderInfoOrigin,subOrderInfo);
            //  从新设定参数
            subOrderInfo.setId(null);
            subOrderInfo.setParentOrderId(orderInfoOrigin.getId());
            subOrderInfo.setWareId(wareId);
            List<OrderDetail> orderDetailList = orderInfoOrigin.getOrderDetailList();
            // 创建一个新的订单集合
            List<OrderDetail> subOrderDetailList = new ArrayList<>();
            for (OrderDetail orderDetail : orderDetailList) {
                for (String skuId : skuIds) {
                    if(orderDetail.getSkuId().equals(skuId)){
                        orderDetail.setId(null);
                        subOrderDetailList.add(orderDetail);
                    }
                }
            }
            // 封装数据
            subOrderInfo.setOrderDetailList(subOrderDetailList);
            // 计算总价
            subOrderInfo.sumTotalAmount();
            // 保存到数据库中
            saveOrder(subOrderInfo);
            // 将拆分后的订单集合返回
            subOrderInfoList.add(subOrderInfo);
        }
        // 更新状态
        updateOrderStatus(orderId,ProcessStatus.SPLIT);
        // 8 返回一个新生成的子订单列表
        return subOrderInfoList;
    }


}
