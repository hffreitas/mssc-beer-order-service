package org.example.beer.order.service.repositories;

import org.example.beer.order.service.domain.BeerOrder;
import org.example.beer.order.service.domain.BeerOrderStatusEnum;
import org.example.beer.order.service.domain.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BeerOrderRepository extends JpaRepository<BeerOrder, UUID> {

    Page<BeerOrder> findAllByCustomer(Customer customer, Pageable pageable);

    List<BeerOrder> findAllByOrderStatus(BeerOrderStatusEnum beerOrderStatusEnum);
}
