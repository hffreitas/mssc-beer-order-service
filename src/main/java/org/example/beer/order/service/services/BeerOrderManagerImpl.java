package org.example.beer.order.service.services;

import org.example.beer.order.service.domain.BeerOrder;
import org.example.beer.order.service.domain.BeerOrderEventEnum;
import org.example.beer.order.service.domain.BeerOrderStatusEnum;
import org.example.beer.order.service.repositories.BeerOrderRepository;
import org.example.beer.order.service.sm.BeerOrderStateChangeInterceptor;
import org.example.brewery.model.BeerOrderDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
@Service
public class BeerOrderManagerImpl implements BeerOrderManager {

    public static final String ORDER_ID_HEADER = "ORDER_ID_HEADER";
    private final StateMachineFactory<BeerOrderStatusEnum, BeerOrderEventEnum> stateMachineFactory;
    private final BeerOrderRepository repository;
    private final BeerOrderStateChangeInterceptor interceptor;

    @Transactional
    @Override
    public BeerOrder newBeerOrder(BeerOrder beerOrder) {
        beerOrder.setId(null);
        beerOrder.setOrderStatus(BeerOrderStatusEnum.NEW);
        BeerOrder savedBeerOrder = repository.saveAndFlush(beerOrder);
        sendBeerOrderEvent(savedBeerOrder, BeerOrderEventEnum.VALIDATE_ORDER);
        return savedBeerOrder;
    }

    @Transactional
    @Override
    public void processValidationResult(UUID beerOrderId, Boolean isValid) {
        log.debug("Process Validation Result for beerOrderId: " + beerOrderId + " Valid? " + isValid);

        Optional<BeerOrder> beerOrderOptional = repository.findById(beerOrderId);

        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            if (isValid) {
                sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.VALIDATION_PASSED);

                //wait for status change
                awaitForStatus(beerOrderId, BeerOrderStatusEnum.VALIDATED);

                Optional<BeerOrder> validatedOrderOptional = repository.findById(beerOrderId);
                validatedOrderOptional.ifPresent(validatedOrder -> {
                    log.info("Found beerById with status: {}", validatedOrder.getOrderStatus());
                    sendBeerOrderEvent(validatedOrder, BeerOrderEventEnum.ALLOCATE_ORDER);
                });
            } else {
                sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.VALIDATION_FAILED);
            }
        }, () -> log.error("Order Not Found|OrderId:{}", beerOrderId));
    }


    @Override
    public void beerOrderAllocationPassed(BeerOrderDto dto) {
        Optional<BeerOrder> beerOrderOptional = repository.findById(dto.getId());
        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.ALLOCATION_SUCCESS);
            awaitForStatus(beerOrder.getId(), BeerOrderStatusEnum.ALLOCATED);
            updateAllocatedQty(dto);
        }, () -> log.error("Order Not Found|OrderId:{}", dto.getId()));
    }

    @Override
    public void beerOrderAllocationPendingInventory(BeerOrderDto dto) {
        Optional<BeerOrder> beerOrderOptional = repository.findById(dto.getId());
        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.ALLOCATION_NO_INVENTORY);
            awaitForStatus(beerOrder.getId(), BeerOrderStatusEnum.PENDING_INVENTORY);
            updateAllocatedQty(dto);
        }, () -> log.error("Order Not Found|OrderId:{}", dto.getId()));
    }

    private void updateAllocatedQty(BeerOrderDto dto) {
        Optional<BeerOrder> allocatedOrderOptional = repository.findById(dto.getId());
        allocatedOrderOptional.ifPresentOrElse(allocatedOrder -> {
            allocatedOrder.getBeerOrderLines().forEach(
                    line -> dto.getBeerOrderLines().forEach(lineDto -> {
                        if (line.getId().equals(lineDto.getId())) {
                            line.setQuantityAllocated(lineDto.getQuantityAllocated());
                        }
                    })
            );

            repository.saveAndFlush(allocatedOrder);
        }, () -> log.error("Order Not Found|OrderId:{}", dto.getId()));
    }

    @Override
    public void beerOrderAllocationFailed(BeerOrderDto dto) {
        Optional<BeerOrder> beerOrderOptional = repository.findById(dto.getId());
        beerOrderOptional.ifPresentOrElse(
                beerOrder -> sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.ALLOCATION_FAILED),
                () -> log.error("Order Not Found|OrderId:{}", dto.getId()));
    }

    @Override
    public void beerOrderPickedUp(UUID id) {
        Optional<BeerOrder> beerOrderOptional = repository.findById(id);

        beerOrderOptional.ifPresentOrElse(beerOrder -> {
                    sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.BEER_ORDER_PICKED_UP);
                }, () -> log.error("Order Not Found|OrderId:{}", id)
        );
    }

    @Override
    public void cancelOrder(UUID id) {
        repository.findById(id).ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.CANCEL_ORDER);
        }, () -> log.error("Order Not Found. Id: {}", id));
    }

    private void sendBeerOrderEvent(BeerOrder beerOrder, BeerOrderEventEnum eventEnum) {
        StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> sm = build(beerOrder);

        Message<BeerOrderEventEnum> message = MessageBuilder.withPayload(eventEnum)
                .setHeader(ORDER_ID_HEADER, beerOrder.getId().toString()).build();
        sm.sendEvent(Mono.just(message)).subscribe();
    }

    private StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> build(BeerOrder beerOrder) {
        StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> sm =
                stateMachineFactory.getStateMachine(beerOrder.getId());

        sm.stopReactively().subscribe();

        sm.getStateMachineAccessor().doWithAllRegions(sma -> {
            sma.addStateMachineInterceptor(interceptor);
            sma.resetStateMachineReactively(new DefaultStateMachineContext<>(beerOrder.getOrderStatus(), null, null, null)).subscribe();
        });

        sm.startReactively().subscribe();

        return sm;
    }

    private void awaitForStatus(UUID beerOrderId, BeerOrderStatusEnum statusEnum) {
        AtomicBoolean found = new AtomicBoolean(false);
        AtomicInteger loopCount = new AtomicInteger(0);

        while (!found.get()) {
            if (loopCount.incrementAndGet() > 10) {
                found.set(true);
                log.debug("Loop Retries exceeded");
            }

            repository.findById(beerOrderId).ifPresentOrElse(beerOrder -> {
                if (beerOrder.getOrderStatus().equals(statusEnum)) {
                    found.set(true);
                    log.debug("Order Found");
                } else {
                    log.debug("Order Status Not Equal. Expected: " + statusEnum.name() + " Found: " + beerOrder.getOrderStatus().name());
                }
            }, () -> log.debug("Order Id Not Found"));

            if (!found.get()) {
                try {
                    log.debug("Sleeping for retry");
                    Thread.sleep(100);
                } catch (Exception e) {
                    // do nothing
                }
            }
        }
    }


}
