package org.example.beer.order.service.services;

import org.example.beer.order.service.config.JmsConfig;
import org.example.beer.order.service.domain.BeerOrder;
import org.example.beer.order.service.domain.BeerOrderLine;
import org.example.beer.order.service.domain.BeerOrderStatusEnum;
import org.example.beer.order.service.domain.Customer;
import org.example.beer.order.service.initializers.WireMockInitializer;
import org.example.beer.order.service.repositories.BeerOrderRepository;
import org.example.beer.order.service.repositories.CustomerRepository;
import org.example.beer.order.service.services.beer.BeerServiceImpl;
import org.example.brewery.model.BeerDto;
import org.example.brewery.model.events.AllocationFailureEvent;
import org.example.brewery.model.events.DeallocateOrderRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ContextConfiguration(initializers = {WireMockInitializer.class})
public class BeerOrderManagerImplIT {

    @Autowired
    BeerOrderManager manager;

    @Autowired
    BeerOrderRepository beerOrderRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    WireMockServer wireMockServer;

    @Autowired
    JmsTemplate jmsTemplate;

    Customer testCustomer;

    UUID beerId = UUID.randomUUID();

    @AfterEach
    public void afterEach() {
        this.wireMockServer.resetAll();
    }

    @BeforeEach
    void setUp() {
        testCustomer = customerRepository.save(Customer.builder()
                .customerName("Test Customer")
                .build());
    }

    @Test
    void testNewToAllocated() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));
        BeerOrder beerOrder = createBeerOrder();

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder founded = beerOrderRepository.findById(beerOrder.getId()).orElseThrow();

            Assertions.assertEquals(BeerOrderStatusEnum.ALLOCATED, founded.getOrderStatus());
        });

        await().untilAsserted(() -> {
            BeerOrder foundedOrder = beerOrderRepository.findById(beerOrder.getId()).orElseThrow();
            BeerOrderLine line = foundedOrder.getBeerOrderLines().iterator().next();
            assertEquals(line.getOrderQuantity(), line.getQuantityAllocated());
        });

        savedBeerOrder = beerOrderRepository.findById(savedBeerOrder.getId()).orElseThrow();
        assertNotNull(savedBeerOrder);
        assertEquals(BeerOrderStatusEnum.ALLOCATED, savedBeerOrder.getOrderStatus());
        savedBeerOrder.getBeerOrderLines().forEach(
                line -> assertEquals(line.getOrderQuantity(), line.getQuantityAllocated()));
    }

    @Test
    void testFailedValidation() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));
        BeerOrder beerOrder = createBeerOrder();
        beerOrder.setCustomerRef("fail-validation");

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder founded = beerOrderRepository.findById(beerOrder.getId()).orElseThrow();

            assertEquals(BeerOrderStatusEnum.VALIDATION_EXCEPTION, founded.getOrderStatus());
        });
    }

    @Test
    void testNewToPickedUp() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));

        BeerOrder beerOrder = createBeerOrder();

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.ALLOCATED, foundOrder.getOrderStatus());
        });

        manager.beerOrderPickedUp(savedBeerOrder.getId());

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.PICKED_UP, foundOrder.getOrderStatus());
        });

        BeerOrder pickedUpOrder = beerOrderRepository.findById(savedBeerOrder.getId()).get();

        assertEquals(BeerOrderStatusEnum.PICKED_UP, pickedUpOrder.getOrderStatus());
    }

    @Test
    void testAllocationFailure() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));

        BeerOrder beerOrder = createBeerOrder();
        beerOrder.setCustomerRef("fail-allocation");

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.ALLOCATION_EXCEPTION, foundOrder.getOrderStatus());
        });

        AllocationFailureEvent allocationFailureEvent = (AllocationFailureEvent) jmsTemplate.receiveAndConvert(JmsConfig.ALLOCATION_FAILURE_QUEUE);

        assertNotNull(allocationFailureEvent);
        assertThat(allocationFailureEvent.getOrderId()).isEqualTo(savedBeerOrder.getId());
    }

    @Test
    void testPartialAllocation() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));

        BeerOrder beerOrder = createBeerOrder();
        beerOrder.setCustomerRef("partial-allocation");

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.PENDING_INVENTORY, foundOrder.getOrderStatus());
        });
    }

    @Test
    void testValidationPendingToCancel() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));

        BeerOrder beerOrder = createBeerOrder();
        beerOrder.setCustomerRef("dont-validate");

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.VALIDATION_PENDING, foundOrder.getOrderStatus());
        });

        manager.cancelOrder(savedBeerOrder.getId());

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.CANCELLED, foundOrder.getOrderStatus());
        });
    }

    @Test
    void testAllocationPendingToCancel() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));

        BeerOrder beerOrder = createBeerOrder();
        beerOrder.setCustomerRef("dont-allocate");

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.ALLOCATION_PENDING, foundOrder.getOrderStatus());
        });

        manager.cancelOrder(savedBeerOrder.getId());

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.CANCELLED, foundOrder.getOrderStatus());
        });
    }

    @Test
    void testAllocatedToCancel() throws JsonProcessingException {
        String upc = "12345";
        BeerDto beerDto = BeerDto.builder().id(beerId).upc(upc).build();

        wireMockServer.stubFor(get(BeerServiceImpl.BEER_UPC_PATH_V1 + upc)
                .willReturn(okJson(objectMapper.writeValueAsString(beerDto))));

        BeerOrder beerOrder = createBeerOrder();

        BeerOrder savedBeerOrder = manager.newBeerOrder(beerOrder);

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.ALLOCATED, foundOrder.getOrderStatus());
        });

        manager.cancelOrder(savedBeerOrder.getId());

        await().untilAsserted(() -> {
            BeerOrder foundOrder = beerOrderRepository.findById(beerOrder.getId()).get();
            assertEquals(BeerOrderStatusEnum.CANCELLED, foundOrder.getOrderStatus());
        });

        DeallocateOrderRequest deallocateOrderRequest = (DeallocateOrderRequest) jmsTemplate.receiveAndConvert(JmsConfig.DEALLOCATE_ORDER_QUEUE);

        assertNotNull(deallocateOrderRequest);
        assertThat(deallocateOrderRequest.getBeerOrderDto().getId()).isEqualTo(savedBeerOrder.getId());
    }

    public BeerOrder createBeerOrder() {
        BeerOrder beerOrder = BeerOrder.builder()
                .customer(testCustomer)
                .build();

        Set<BeerOrderLine> lines = new HashSet<>();
        lines.add(BeerOrderLine.builder()
                .beerId(beerId)
                .upc("12345")
                .orderQuantity(1)
                .beerOrder(beerOrder)
                .build());

        beerOrder.setBeerOrderLines(lines);
        return beerOrder;
    }
}
