package org.example.beer.order.service.services.testcomponents;

import org.example.beer.order.service.config.JmsConfig;
import org.example.brewery.model.events.ValidateOrderRequest;
import org.example.brewery.model.events.ValidateOrderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class BeerOrderValidationListener {
    private final JmsTemplate jmsTemplate;

    @JmsListener(destination = JmsConfig.VALIDATE_ORDER_QUEUE)
    public void listen(Message message) {
        boolean isValid = true;
        boolean sendResponse = true;

        ValidateOrderRequest request = (ValidateOrderRequest) message.getPayload();

        if (request.getBeerOrder().getCustomerRef() != null) {
            if ("fail-validation".equals(request.getBeerOrder().getCustomerRef())) {
                isValid = false;
            } else if (request.getBeerOrder().getCustomerRef().equals("dont-validate")) {
                sendResponse = false;
            }
        }

        log.info("Received order: {}", request.getBeerOrder().getOrderStatus());

        if (sendResponse) {
            jmsTemplate.convertAndSend(JmsConfig.VALIDATE_ORDER_RESPONSE_QUEUE,
                    ValidateOrderResult.builder()
                            .isValid(isValid)
                            .orderId(request.getBeerOrder().getId())
                            .build());
        }
    }
}
