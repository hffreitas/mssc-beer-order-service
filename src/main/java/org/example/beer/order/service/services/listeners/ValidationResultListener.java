package org.example.beer.order.service.services.listeners;

import org.example.brewery.model.events.ValidateOrderResult;
import org.example.beer.order.service.config.JmsConfig;
import org.example.beer.order.service.services.BeerOrderManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class ValidationResultListener {

    private final BeerOrderManager manager;

    @JmsListener(destination = JmsConfig.VALIDATE_ORDER_RESPONSE_QUEUE)
    public void listen(ValidateOrderResult result) {
        final UUID beerOrderId = result.getOrderId();

        log.info("Validation result|beerId:{}", beerOrderId);

        manager.processValidationResult(beerOrderId, result.getIsValid());
    }
}
