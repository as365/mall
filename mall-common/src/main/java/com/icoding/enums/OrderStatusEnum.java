package com.icoding.enums;

/**
 * 支付方式
 */
public enum OrderStatusEnum {
  /**
   * 待支付
   */
  WAIT_PAY(10, "待支付"),
  /**
   * 待发货
   */
  WAIT_DELIVER(20, "已付款, 待发货"),
  /**
   * 待收货
   */
  WAIT_RECEIVE(30, "已发货，待收货"),
  /**
   * 交易成功
   */
  SUCCESS(40, "交易成功"),
  /**
   * 交易取消
   */
  CLOSE(50, "交易关闭");

  private Integer type;
  private String value;
  OrderStatusEnum(Integer type, String value) {
    this.type = type;
    this.value = value;
  }

  public Integer getType() {
    return type;
  }

  public String getValue() {
    return value;
  }
}
