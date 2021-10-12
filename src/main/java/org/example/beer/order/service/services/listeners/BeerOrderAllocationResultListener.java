package org.example.beer.order.service.services.listeners;

import org.example.beer.order.service.config.JmsConfig;
import org.example.beer.order.service.services.BeerOrderManager;
import org.example.brewery.model.events.AllocateOrderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class BeerOrderAllocationResultListener {
    private final BeerOrderManager manager;

    @JmsListener(destination = JmsConfig.ALLOCATE_ORDER_RESPONSE_QUEUE)
    public void listen(AllocateOrderResult result){
        if(!result.getAllocationError() && !result.getPendingInventory()){
            manager.beerOrderAllocationPassed(result.getBeerOrderDto());
        } else if (!result.getAllocationError() && result.getPendingInventory()){
            manager.beerOrderAllocationPendingInventory(result.getBeerOrderDto());
        } else if (result.getAllocationError()){
            manager.beerOrderAllocationFailed(result.getBeerOrderDto());
        }
    }
}
