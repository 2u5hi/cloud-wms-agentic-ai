package com.cloudwms.core.inventory.domain;

/** The change a movement makes to one location's on-hand quantity. */
public record StockDelta(StockKey key, int delta) {

}
