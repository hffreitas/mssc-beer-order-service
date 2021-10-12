package org.example.beer.order.service.services;

import org.example.beer.order.service.domain.BeerOrder;
import org.example.beer.order.service.domain.BeerOrderStatusEnum;
import org.example.beer.order.service.domain.Customer;
import org.example.beer.order.service.repositories.BeerOrderRepository;
import org.example.beer.order.service.repositories.CustomerRepository;
import org.example.beer.order.service.web.mappers.BeerOrderMapper;
import org.example.brewery.model.BeerOrderDto;
import org.example.brewery.model.BeerOrderPagedList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BeerOrderServiceImpl implements BeerOrderService {

    private final BeerOrderRepository beerOrderRepository;
    private final CustomerRepository customerRepository;
    private final BeerOrderMapper beerOrderMapper;
    private final BeerOrderManager manager;

    @Override
    public BeerOrderPagedList listOrders(UUID customerId, PageRequest pageRequest) {
        Optional<Customer> customerOptional = customerRepository.findById(customerId);

        if (customerOptional.isPresent()) {
            Page<BeerOrder> beerOrderPage = beerOrderRepository.findAllByCustomer(customerOptional.get(), pageRequest);

            return new BeerOrderPagedList(beerOrderPage.stream()
                    .map(beerOrderMapper::beerOrderToDto)
                    .collect(Collectors.toList()), PageRequest.of(
                                    beerOrderPage.getPageable().getPageNumber(),
                                    beerOrderPage.getPageable().getPageSize()),
                                    beerOrderPage.getTotalElements());
        } else {
            return null;
        }
    }

    @Transactional
    @Override
    public BeerOrderDto placeOrder(UUID customerId, BeerOrderDto beerOrderDto) {
        Optional<Customer> customerOptional = customerRepository.findById(customerId);

        if(customerOptional.isPresent()){
            BeerOrder beerOrder = beerOrderMapper.dtoToBeerOrder(beerOrderDto);
            beerOrder.setId(null);
            beerOrder.setCustomer(customerOptional.get());
            beerOrder.setOrderStatus(BeerOrderStatusEnum.NEW);
            beerOrder.getBeerOrderLines().forEach(line -> line.setBeerOrder(beerOrder));

            BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

            log.debug("Saved Beer Order: {}", beerOrder.getId());

            return beerOrderMapper.beerOrderToDto(savedBeerOrder);
        }

        throw new RuntimeException("Customer not found");
    }

    @Override
    public BeerOrderDto getOrderById(UUID customerId, UUID orderId) {
        return beerOrderMapper.beerOrderToDto(getOrder(customerId, orderId));
    }

    private BeerOrder getOrder(UUID customerId, UUID orderId) {
        Optional<Customer> customerOptional = customerRepository.findById(customerId);

        if(customerOptional.isPresent()){
            Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(orderId);

            if(beerOrderOptional.isPresent()){
                BeerOrder beerOrder = beerOrderOptional.get();

                if(beerOrder.getCustomer().getId().equals(customerId)){
                    return beerOrder;
                }
            }

            throw new RuntimeException("Beer Order Not Found");
        }

        throw new RuntimeException("Customer Not Found");
    }

    @Override
    public void pickupOrder(UUID customerId, UUID orderId) {
        manager.beerOrderPickedUp(orderId);
    }
}
