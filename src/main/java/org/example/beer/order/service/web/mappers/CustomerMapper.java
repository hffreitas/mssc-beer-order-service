package org.example.beer.order.service.web.mappers;

import org.example.beer.order.service.domain.Customer;
import org.example.brewery.model.CustomerDto;
import org.mapstruct.Mapper;

@Mapper(uses = {DateMapper.class})
public interface CustomerMapper {
    CustomerDto customerToDto(Customer customer);

    Customer dtoToCustomer(CustomerDto dto);
}
