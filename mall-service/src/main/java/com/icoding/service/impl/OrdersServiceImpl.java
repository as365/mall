package com.icoding.service.impl;

import com.icoding.bo.ShopcartItemBO;
import com.icoding.bo.SubmitOrderBO;
import com.icoding.enums.OrderStatusEnum;
import com.icoding.enums.YesOrNo;
import com.icoding.mapper.*;
import com.icoding.pojo.*;
import com.icoding.service.ItemsService;
import com.icoding.service.OrdersService;
import com.icoding.utils.JSONResult;
import com.icoding.utils.PagedGridResult;
import com.icoding.utils.RedisOperator;
import com.icoding.vo.OrderStatusCountVO;
import com.icoding.vo.UserCenterOrderVO;
import org.n3r.idworker.Sid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@SuppressWarnings("ALL")
@Service
public class OrdersServiceImpl implements OrdersService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OrdersServiceImpl.class);

  @Autowired
  Sid sid;

  @Autowired
  UserAddressMapper addressMapper;

  @Autowired
  ItemsSpecMapper itemsSpecMapper;

  @Autowired
  ItemsMapper itemsMapper;

  @Autowired
  ItemsService itemsService;

  @Autowired
  ItemsImgMapper itemsImgMapper;

  @Autowired
  OrdersMapper ordersMapper;

  @Autowired
  OrderItemsMapper orderItemMapper;

  @Autowired
  OrderStatusMapper orderStatusMapper;

  @Autowired
  RedisOperator redisOperator;

  @Transactional(propagation = Propagation.REQUIRED)
  @Override
  public JSONResult createOrder(List<ShopcartItemBO> shopcartItems, SubmitOrderBO submitOrderBO) {
    String userId = submitOrderBO.getUserId();
    String addressId = submitOrderBO.getAddressId();
    String lefMsg = submitOrderBO.getLeftMsg();
    String itemSpecIds = submitOrderBO.getItemSpecIds();
    Integer payMethod = submitOrderBO.getPayMethod();
    // 邮费
    Integer postAmount = 0;
    // 查询订单的地址信息
    UserAddress address = addressMapper.selectByPrimaryKey(addressId);

    // 1 订单表数据保存
    String orderId = sid.nextShort();
    Orders newOrder = Orders.OrdersBuilder.anOrders()
            .withId(orderId)
            .withUserId(userId)
            .withReceiverAddress(String.format("%s省,%s市,%s,%s", address.getProvince(), address.getCity(), address.getDistrict(), address.getDetail()))
            .withReceiverName(address.getReceiver())
            .withReceiverMobile(address.getMobile())
//            .withTotalAmount()
//            .withRealPayAmount()
            .withPostAmount(postAmount)
            .withPayMethod(payMethod)
            .withLeftMsg(lefMsg)
            .withIsComment(YesOrNo.NO.getType())
            .withIsDelete(YesOrNo.NO.getType())
            .withCreatedTime(new Date())
            .withUpdatedTime(new Date())
            .build();

    // 2 根据itemSpecIds 保存订单商品信息表
    String[] itemSpecIdArr = itemSpecIds.split(",");
    // 商品原价累计
    int totalAmount = 0;
    // 实付金额累计
    int realPayAmount = 0;

    for(String itemSpecId : itemSpecIdArr) {
      // 整合redis后，商品购买的数量重新从redis购物车中获取
      int buyCounts = 0;

      Optional<ShopcartItemBO> first = shopcartItems.stream().filter(item -> {
        return item.getSpecId().equals(itemSpecId);
      }).findFirst();

      if(first.isPresent()) {
        buyCounts = first.get().getBuyCounts();
      } else {
        return JSONResult.errMsg("购物车中不包含商品：" + itemSpecId);
      }

      // 2.1 根据规格id查询 查询规格的具体信息，主要获取价格
      ItemsSpec itemsSpec = itemsSpecMapper.selectByPrimaryKey(itemSpecId);
      totalAmount += itemsSpec.getPriceNormal() * buyCounts;
      realPayAmount += itemsSpec.getPriceDiscount() * buyCounts;
      // 2.2 根据商品id获取商品信息及商品图片
      String itemId = itemsSpec.getItemId();
      Items items = itemsMapper.queryItemById(itemId);
      String imgUrl = itemsImgMapper.queryItemMainImg(itemId);
      // 2.3 循环保存子订单数据到数据库
      String orderItemId = sid.nextShort();
      OrderItems newOrderItem = OrderItems.OrderItemsBuilder.anOrderItems()
              .withId(orderItemId)
              .withItemId(itemId)
              .withItemImg(imgUrl)
              .withItemName(items.getItemName())
              .withItemSpecId(itemSpecId)
              .withItemSpecName(itemsSpec.getName())
              .withOrderId(orderId)
              .withBuyCounts(buyCounts)
              .withPrice(itemsSpec.getPriceDiscount())
              .build();
      // 2.4 订单下属商品 入库
      orderItemMapper.insert(newOrderItem);

      // 2.5 在用户提交订单以后， 需要扣减库存
      itemsService.decreaseItemSpecStock(itemSpecId, buyCounts);
    }

    // 1.1 订单表需要计算该笔订单的总金额和实际支付金额 才能入库，需要迭代订单涉及的所有商品规格
    newOrder.setTotalAmount(totalAmount);
    newOrder.setRealPayAmount(realPayAmount);
    // 1.2 订单数据 入库
    ordersMapper.insert(newOrder);

    // 3 保存订单状态表
    OrderStatus orderStatus = new OrderStatus();
    orderStatus.setOrderStatus(OrderStatusEnum.WAIT_PAY.getType());
    orderStatus.setOrderId(orderId);
    orderStatus.setCreatedTime(new Date());
    orderStatusMapper.insert(orderStatus);

    return JSONResult.ok(orderId);
  }

  /**
   * 修改订单状态
   * @param orderId
   * @param time
   * @param orderStatus
   */
  @Transactional(propagation = Propagation.REQUIRED)
  @Override
  public void updateOrderStatus(String orderId, String time, Integer orderStatus) {
    orderStatusMapper.updateOrderStatus(orderId, time, orderStatus);
  }

  /**
   * 取消超时未支付订单(1天)
   */
  @Transactional(propagation = Propagation.REQUIRED)
  @Override
  public void closeOrdersWhoseStatusIsWaitPayAndTimeOut() {
    LOGGER.info("*************** 关闭超时未支付订单 start ****************");
    List<OrderStatus> watiPayAndTimeOutOrders = orderStatusMapper.queryOrdersWhoseStatusIsWaitPayAndTimeOut();
    watiPayAndTimeOutOrders.stream().forEach(this::doClose);
    LOGGER.info("*************** 关闭超时未支付订单 end ****************");
  }

  /**
   * 用户中心->我的订单->分类订单
   * @param userId
   * @param orderStatus
   * @param page
   * @param pageSize
   * @return
   */
  @Transactional(propagation = Propagation.REQUIRED)
  @Override
  public PagedGridResult<UserCenterOrderVO> queryOrdersByStatus(String userId, Integer orderStatus, Integer page, Integer pageSize) {
    if(page == null) {
      page = 1;
    }
    if(pageSize == null) {
      pageSize = 20;
    }

    int start = (page - 1) * pageSize;
    int end = pageSize * page;

    int totalCounts = ordersMapper.getOrdersCountByStatus(userId, orderStatus);
    int totalPages = totalCounts % pageSize;

    Map<String, Object> queryParams = new HashMap(4);
    queryParams.put("userId", userId);
    queryParams.put("orderStatus", orderStatus);
    queryParams.put("start", start);
    queryParams.put("end", end);

    List<UserCenterOrderVO> rows = ordersMapper.getOrdersByStatus(queryParams);

    PagedGridResult<UserCenterOrderVO> result = new PagedGridResult<>();
    result.setPage(page);
    result.setTotal(totalPages);
    result.setRecords(totalCounts);
    result.setRows(rows);

    return result;
  }

  /**
   * 根据用户id和订单id 将订单is_delete 状态改为1
   * @param userId
   * @param orderId
   */
  @Transactional(propagation = Propagation.REQUIRED)
  @Override
  public void deleteOrder(String userId, String orderId) {
    ordersMapper.setOrderDeleted(userId, orderId);
  }

  @Transactional(propagation = Propagation.SUPPORTS)
  @Override
  public Orders queryOrderByUserIdAndOrderId(String userId, String orderId) {
    return ordersMapper.queryOrderByUserIdAndOrderId(userId, orderId);
  }

  /**
   * 执行关闭订单操作
   * @param orderStatus
   */
  public void doClose(OrderStatus orderStatus) {
    orderStatusMapper.updateOrdersStatusByOrderId(orderStatus.getOrderId(), OrderStatusEnum.CLOSE.getType());
    LOGGER.info("close order: {}", orderStatus.getOrderId());
  }

  @Transactional(propagation = Propagation.SUPPORTS)
  @Override
  public List<OrderItems> getItemsByOrderId(String orderId) {
    return orderItemMapper.getOrderItemsByOrderId(orderId);
  }

  @Transactional(propagation = Propagation.REQUIRED)
  @Override
  public void setOrderIsCommented(String userId, String orderId) {
    ordersMapper.setOrderIsCommented(userId, orderId);
  }

  /**
   * 用于验证用户和订单是否有关联关系，防止恶意篡改他人订单
   * @param userId
   * @param orderId
   * @return
   */
  @Override
  public JSONResult checkOrder(String userId, String orderId) {
    Orders order = queryOrderByUserIdAndOrderId(userId, orderId);
    if(order == null) {
      return JSONResult.errMsg("查无此订单");
    }
    return JSONResult.ok(order);
  }

  @Transactional(propagation = Propagation.SUPPORTS)
  @Override
  public OrderStatusCountVO getOrderStatusCounts(String userId) {
    Map<String, Object> map = new HashMap<>(4);
    map.put("userId", userId);

    // 待支付
    map.put("orderStatus", OrderStatusEnum.WAIT_PAY.getType());
    int waitPayCounts = ordersMapper.getMyOrderStatusCounts(map);

    // 待发货
    map.put("orderStatus", OrderStatusEnum.WAIT_DELIVER.getType());
    int waitDeliverCounts = ordersMapper.getMyOrderStatusCounts(map);

    // 待收货
    map.put("orderStatus", OrderStatusEnum.WAIT_RECEIVE.getType());
    int waitReceiveCounts = ordersMapper.getMyOrderStatusCounts(map);

    // 待评论
    map.put("orderStatus", OrderStatusEnum.SUCCESS.getType());
    map.put("isComment", YesOrNo.NO.getType());
    int waitCommentCounts = ordersMapper.getMyOrderStatusCounts(map);

    OrderStatusCountVO orderStatusCountVO = new OrderStatusCountVO();
    orderStatusCountVO.setWaitPayCounts(waitPayCounts);
    orderStatusCountVO.setWaitDeliverCounts(waitDeliverCounts);
    orderStatusCountVO.setWaitReceiveCounts(waitReceiveCounts);
    orderStatusCountVO.setWaitCommentCounts(waitCommentCounts);

    return orderStatusCountVO;
  }

  @Transactional(propagation = Propagation.SUPPORTS)
  @Override
  public PagedGridResult getOrdersTrend(String userId, Integer page, Integer pageSize) {
    if(page == null) {
      page = 1;
    }
    if(pageSize == null) {
      pageSize = 20;
    }

    int start = (page - 1) * pageSize;
    int end = pageSize * page;

    int totalCounts = ordersMapper.getOrderTrendCounts(userId);
    int totalPages = totalCounts % pageSize;

    Map<String, Object> queryParams = new HashMap(3);
    queryParams.put("userId", userId);
    queryParams.put("start", start);
    queryParams.put("end", end);

    List<OrderStatus> rows = ordersMapper.getOrderTrendList(queryParams);

    PagedGridResult<OrderStatus> result = new PagedGridResult<>();
    result.setPage(page);
    result.setTotal(totalPages);
    result.setRecords(totalCounts);
    result.setRows(rows);
    return result;
  }
}
