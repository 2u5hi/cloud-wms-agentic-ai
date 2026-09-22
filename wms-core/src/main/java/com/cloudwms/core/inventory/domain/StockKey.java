package com.cloudwms.core.inventory.domain;

/** Stock is tracked per SKU per location. */
public record StockKey(long locationId, long skuId) {

}
