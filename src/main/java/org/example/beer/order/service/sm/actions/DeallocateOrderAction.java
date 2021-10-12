package org.example.beer.order.service.sm.actions;

import org.example.beer.order.service.config.JmsConfig;
import org.example.beer.order.service.domain.BeerOrder;
import org.example.beer.order.service.domain.BeerOrderEventEnum;
import org.example.beer.order.service.domain.BeerOrderStatusEnum;
import org.example.beer.order.service.repositories.BeerOrderRepository;
import org.example.beer.order.service.services.BeerOrderManagerImpl;
import org.example.beer.order.service.web.mappers.BeerOrderMapper;
import org.example.brewery.model.events.DeallocateOrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class DeallocateOrderAction implements Action<BeerOrderStatusEnum, BeerOrderEventEnum> {

    private final BeerOrderRepository repository;
    private final JmsTemplate jmsTemplate;
    private final BeerOrderMapper mapper;

    @Override
    public void execute(StateContext<BeerOrderStatusEnum, BeerOrderEventEnum> context) {
        String beerOrderId = (String) context.getMessage().getHeaders().get(BeerOrderManagerImpl.ORDER_ID_HEADER);
        Optional<BeerOrder> beerOrderOptional = repository.findById(UUID.fromString(beerOrderId));

        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            jmsTemplate.convertAndSend(JmsConfig.DEALLOCATE_ORDER_QUEUE,
                    DeallocateOrderRequest.builder()
                            .beerOrderDto(mapper.beerOrderToDto(beerOrder))
                            .build());
            log.debug("Sent Allocation request|OrderId:{}", beerOrderId);
        }, () -> log.error("Order Not Found|BeerId:{}", beerOrderId));
    }
}
