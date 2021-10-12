package org.example.beer.order.service.services;

import org.example.brewery.model.BeerOrderDto;
import org.example.brewery.model.BeerOrderPagedList;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

public interface BeerOrderService {
    BeerOrderPagedList listOrders(UUID customerId, PageRequest pageRequest);

    BeerOrderDto placeOrder(UUID customerId, BeerOrderDto beerOrderDto);

    BeerOrderDto getOrderById(UUID customerId, UUID orderId);

    void pickupOrder(UUID customerId, UUID orderId);
}
