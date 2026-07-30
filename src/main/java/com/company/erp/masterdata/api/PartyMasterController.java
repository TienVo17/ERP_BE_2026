package com.company.erp.masterdata.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.masterdata.api.MasterDataModels.*;
import com.company.erp.masterdata.application.MasterDataSupport;
import com.company.erp.masterdata.application.PartyMasterService;
import com.company.erp.masterdata.application.PartyMasterService.PartyType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/master-data") @Validated
public class PartyMasterController {
    private final PartyMasterService service;
    public PartyMasterController(PartyMasterService service){this.service=service;}

    @GetMapping("/customers") @PreAuthorize("hasAuthority('CUSTOMER:VIEW')")
    PageResponse<CustomerResponse> customers(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="25")@Min(1)@Max(100)int size,@RequestParam(required=false)List<String> sort,@RequestParam(required=false)String status,@RequestParam(required=false)String name,@RequestParam(required=false)String shortName){return service.customers(page,size,sort,status,name,shortName);}
    @PostMapping("/customers") @PreAuthorize("hasAuthority('CUSTOMER:CREATE')")
    ResponseEntity<CustomerResponse> createCustomer(@Valid@RequestBody CustomerCreateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return ResponseEntity.status(201).body(service.createCustomer(q,p(a),rid(r)));}
    @GetMapping("/customers/{id}") @PreAuthorize("hasAuthority('CUSTOMER:VIEW')") CustomerResponse customer(@PathVariable UUID id){return service.customer(id);}
    @PutMapping("/customers/{id}") @PreAuthorize("hasAuthority('CUSTOMER:UPDATE')") CustomerResponse updateCustomer(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,@Valid@RequestBody CustomerUpdateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return service.updateCustomer(id,v(m),q,p(a),rid(r));}
    @PostMapping("/customers/{id}/archive") @PreAuthorize("hasAuthority('CUSTOMER:ARCHIVE')") CustomerResponse archiveCustomer(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,JwtAuthenticationToken a,HttpServletRequest r){return service.archiveCustomer(id,v(m),p(a),rid(r));}

    @GetMapping("/suppliers") @PreAuthorize("hasAuthority('SUPPLIER:VIEW')") PageResponse<SupplierResponse> suppliers(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="25")@Min(1)@Max(100)int size,@RequestParam(required=false)List<String> sort,@RequestParam(required=false)String status,@RequestParam(required=false)String name){return service.suppliers(page,size,sort,status,name);}
    @PostMapping("/suppliers") @PreAuthorize("hasAuthority('SUPPLIER:CREATE')") ResponseEntity<SupplierResponse> createSupplier(@Valid@RequestBody SupplierRequest q,JwtAuthenticationToken a,HttpServletRequest r){return ResponseEntity.status(201).body(service.createSupplier(q,p(a),rid(r)));}
    @GetMapping("/suppliers/{id}") @PreAuthorize("hasAuthority('SUPPLIER:VIEW')") SupplierResponse supplier(@PathVariable UUID id){return service.supplier(id);}
    @PutMapping("/suppliers/{id}") @PreAuthorize("hasAuthority('SUPPLIER:UPDATE')") SupplierResponse updateSupplier(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,@Valid@RequestBody SupplierRequest q,JwtAuthenticationToken a,HttpServletRequest r){return service.updateSupplier(id,v(m),q,p(a),rid(r));}
    @PostMapping("/suppliers/{id}/archive") @PreAuthorize("hasAuthority('SUPPLIER:ARCHIVE')") SupplierResponse archiveSupplier(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,JwtAuthenticationToken a,HttpServletRequest r){return service.archiveSupplier(id,v(m),p(a),rid(r));}

    @GetMapping("/customers/{id}/contacts") @PreAuthorize("hasAuthority('CUSTOMER:VIEW')") PageResponse<ContactResponse> customerContacts(@PathVariable UUID id,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="25")@Min(1)@Max(100)int size,@RequestParam(required=false)List<String> sort,@RequestParam(required=false)String status){return service.contacts(PartyType.CUSTOMER,id,page,size,sort,status);}
    @PostMapping("/customers/{id}/contacts") @PreAuthorize("hasAuthority('CUSTOMER:UPDATE')") ResponseEntity<ContactResponse> createCustomerContact(@PathVariable UUID id,@Valid@RequestBody ContactCreateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return ResponseEntity.status(201).body(service.createContact(PartyType.CUSTOMER,id,q,p(a),rid(r)));}
    @GetMapping("/customers/{id}/contacts/{contactId}") @PreAuthorize("hasAuthority('CUSTOMER:VIEW')") ContactResponse customerContact(@PathVariable UUID id,@PathVariable UUID contactId){return service.contact(PartyType.CUSTOMER,id,contactId);}
    @PutMapping("/customers/{id}/contacts/{contactId}") @PreAuthorize("hasAuthority('CUSTOMER:UPDATE')") ContactResponse updateCustomerContact(@PathVariable UUID id,@PathVariable UUID contactId,@RequestHeader(HttpHeaders.IF_MATCH)String m,@Valid@RequestBody ContactUpdateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return service.updateContact(PartyType.CUSTOMER,id,contactId,v(m),q,p(a),rid(r));}
    @PostMapping("/customers/{id}/contacts/{contactId}/archive") @PreAuthorize("hasAuthority('CUSTOMER:UPDATE')") ContactResponse archiveCustomerContact(@PathVariable UUID id,@PathVariable UUID contactId,@RequestHeader(HttpHeaders.IF_MATCH)String m,JwtAuthenticationToken a,HttpServletRequest r){return service.archiveContact(PartyType.CUSTOMER,id,contactId,v(m),p(a),rid(r));}

    @GetMapping("/suppliers/{id}/contacts") @PreAuthorize("hasAuthority('SUPPLIER:VIEW')") PageResponse<ContactResponse> supplierContacts(@PathVariable UUID id,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="25")@Min(1)@Max(100)int size,@RequestParam(required=false)List<String> sort,@RequestParam(required=false)String status){return service.contacts(PartyType.SUPPLIER,id,page,size,sort,status);}
    @PostMapping("/suppliers/{id}/contacts") @PreAuthorize("hasAuthority('SUPPLIER:UPDATE')") ResponseEntity<ContactResponse> createSupplierContact(@PathVariable UUID id,@Valid@RequestBody ContactCreateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return ResponseEntity.status(201).body(service.createContact(PartyType.SUPPLIER,id,q,p(a),rid(r)));}
    @GetMapping("/suppliers/{id}/contacts/{contactId}") @PreAuthorize("hasAuthority('SUPPLIER:VIEW')") ContactResponse supplierContact(@PathVariable UUID id,@PathVariable UUID contactId){return service.contact(PartyType.SUPPLIER,id,contactId);}
    @PutMapping("/suppliers/{id}/contacts/{contactId}") @PreAuthorize("hasAuthority('SUPPLIER:UPDATE')") ContactResponse updateSupplierContact(@PathVariable UUID id,@PathVariable UUID contactId,@RequestHeader(HttpHeaders.IF_MATCH)String m,@Valid@RequestBody ContactUpdateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return service.updateContact(PartyType.SUPPLIER,id,contactId,v(m),q,p(a),rid(r));}
    @PostMapping("/suppliers/{id}/contacts/{contactId}/archive") @PreAuthorize("hasAuthority('SUPPLIER:UPDATE')") ContactResponse archiveSupplierContact(@PathVariable UUID id,@PathVariable UUID contactId,@RequestHeader(HttpHeaders.IF_MATCH)String m,JwtAuthenticationToken a,HttpServletRequest r){return service.archiveContact(PartyType.SUPPLIER,id,contactId,v(m),p(a),rid(r));}
    private static com.company.erp.identity.security.ErpPrincipal p(JwtAuthenticationToken a){return MasterDataSupport.principal(a);} private static long v(String m){return MasterDataSupport.version(m);} private static String rid(HttpServletRequest r){return MasterDataSupport.requestId(r);}
}
