package com.company.erp.masterdata.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.masterdata.api.MasterDataModels.*;
import com.company.erp.masterdata.infrastructure.PartyMasterJdbcRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartyMasterService {
    public enum PartyType { CUSTOMER, SUPPLIER }
    private final PartyMasterJdbcRepository repository;
    private final AuditEventWriter audit;
    public PartyMasterService(PartyMasterJdbcRepository repository,AuditEventWriter audit){this.repository=repository;this.audit=audit;}

    public PageResponse<CustomerResponse> customers(int page,int size,List<String> requested,String status,String name,String shortName){AdminQuery.validatePage(page,size);String s=MasterDataSupport.status(status),n=MasterDataSupport.optionalText(name),sn=shortName==null||shortName.isBlank()?null:MasterDataSupport.canonicalCode(shortName);List<String> sort=repository.customerSort(requested);return MasterDataSupport.page(repository.customers(page,size,s,n,sn,sort),page,size,repository.countCustomers(s,n,sn),AdminQuery.filters("status",s,"name",n,"shortName",sn),sort);}
    public CustomerResponse customer(UUID id){return repository.customer(id).orElseThrow(()->new ResourceNotFoundException("customer",id.toString()));}
    @Transactional public CustomerResponse createCustomer(CustomerCreateRequest q,ErpPrincipal a,String rid){CustomerCreateRequest n=new CustomerCreateRequest(MasterDataSupport.canonicalCode(q.shortName()),text(q.name()),opt(q.address()),opt(q.telephone()),MasterDataSupport.canonicalCode(q.currencyCode()));if(repository.customerKeyExists(n.shortName()))duplicate();UUID id=repository.createCustomer(n,a.user().id());CustomerResponse after=customer(id);write(a,"CREATE","CUSTOMER",id,rid,null,summary(after));return after;}
    @Transactional public CustomerResponse updateCustomer(UUID id,long v,CustomerUpdateRequest q,ErpPrincipal a,String rid){try{CustomerResponse before=customer(id);if(repository.customerUsed(id))MasterDataSupport.inUse("Customer");CustomerUpdateRequest n=new CustomerUpdateRequest(text(q.name()),opt(q.address()),opt(q.telephone()),MasterDataSupport.canonicalCode(q.currencyCode()));if(repository.updateCustomer(id,v,n,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.customer(id).isPresent());CustomerResponse after=customer(id);write(a,"UPDATE","CUSTOMER",id,rid,summary(before),summary(after));return after;}catch(DataIntegrityViolationException e){throw MasterDataSupport.mapUsageRace(e,"Customer");}}
    @Transactional public CustomerResponse archiveCustomer(UUID id,long v,ErpPrincipal a,String rid){try{CustomerResponse before=customer(id);if(repository.customerUsed(id))MasterDataSupport.inUse("Customer");if(repository.archiveCustomer(id,v,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.customer(id).isPresent());CustomerResponse after=customer(id);write(a,"ARCHIVE","CUSTOMER",id,rid,summary(before),summary(after));return after;}catch(DataIntegrityViolationException e){throw MasterDataSupport.mapUsageRace(e,"Customer");}}

    public PageResponse<SupplierResponse> suppliers(int page,int size,List<String> requested,String status,String name){AdminQuery.validatePage(page,size);String s=MasterDataSupport.status(status),n=opt(name);List<String> sort=repository.supplierSort(requested);return MasterDataSupport.page(repository.suppliers(page,size,s,n,sort),page,size,repository.countSuppliers(s,n),AdminQuery.filters("status",s,"name",n),sort);}
    public SupplierResponse supplier(UUID id){return repository.supplier(id).orElseThrow(()->new ResourceNotFoundException("supplier",id.toString()));}
    @Transactional public SupplierResponse createSupplier(SupplierRequest q,ErpPrincipal a,String rid){SupplierRequest n=new SupplierRequest(text(q.name()),opt(q.address()),opt(q.telephone()));if(repository.supplierNameExists(n.name(),null))duplicate();UUID id=repository.createSupplier(n,a.user().id());SupplierResponse after=supplier(id);write(a,"CREATE","SUPPLIER",id,rid,null,summary(after));return after;}
    @Transactional public SupplierResponse updateSupplier(UUID id,long v,SupplierRequest q,ErpPrincipal a,String rid){SupplierResponse before=supplier(id);SupplierRequest n=new SupplierRequest(text(q.name()),opt(q.address()),opt(q.telephone()));if(repository.supplierNameExists(n.name(),id))duplicate();if(repository.updateSupplier(id,v,n,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.supplier(id).isPresent());SupplierResponse after=supplier(id);write(a,"UPDATE","SUPPLIER",id,rid,summary(before),summary(after));return after;}
    @Transactional public SupplierResponse archiveSupplier(UUID id,long v,ErpPrincipal a,String rid){SupplierResponse before=supplier(id);if(repository.supplierUsed(id))MasterDataSupport.inUse("Supplier");if(repository.archiveSupplier(id,v,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.supplier(id).isPresent());SupplierResponse after=supplier(id);write(a,"ARCHIVE","SUPPLIER",id,rid,summary(before),summary(after));return after;}

    public PageResponse<ContactResponse> contacts(PartyType type,UUID owner,int page,int size,List<String> requested,String status){ensureOwner(type,owner);AdminQuery.validatePage(page,size);String s=MasterDataSupport.status(status);List<String> sort=repository.contactSort(requested);String[] t=contactTable(type);return MasterDataSupport.page(repository.listContacts(t[0],t[1],owner,page,size,s,sort),page,size,repository.countContacts(t[0],t[1],owner,s),AdminQuery.filters("status",s),sort);}
    public ContactResponse contact(PartyType type,UUID owner,UUID id){ensureOwner(type,owner);String[] t=contactTable(type);return repository.contact(t[0],t[1],owner,id).orElseThrow(()->new ResourceNotFoundException("contact",id.toString()));}
    @Transactional public ContactResponse createContact(PartyType type,UUID owner,ContactCreateRequest q,ErpPrincipal a,String rid){ensureOwner(type,owner);String[] t=contactTable(type);repository.lockOwner(type==PartyType.CUSTOMER?"customer":"supplier",owner);ContactCreateRequest n=new ContactCreateRequest(opt(q.division()),text(q.name()),opt(q.telephone()),opt(q.email()),opt(q.remark()),q.isDefault());List<ContactResponse> demoted=Boolean.TRUE.equals(n.isDefault())?repository.activeDefaultsExcept(t[0],t[1],owner,null):List.of();UUID id=repository.createContact(t[0],t[1],owner,n,a.user().id());auditDemotions(a,type,owner,rid,demoted,t);ContactResponse after=contact(type,owner,id);write(a,"CREATE",type+"_CONTACT",id,rid,null,contactSummary(after));return after;}
    @Transactional public ContactResponse updateContact(PartyType type,UUID owner,UUID id,long v,ContactUpdateRequest q,ErpPrincipal a,String rid){ContactResponse before=contact(type,owner,id);String[] t=contactTable(type);repository.lockOwner(type==PartyType.CUSTOMER?"customer":"supplier",owner);ContactUpdateRequest n=new ContactUpdateRequest(opt(q.division()),text(q.name()),opt(q.telephone()),opt(q.email()),opt(q.remark()),q.isDefault());List<ContactResponse> demoted=Boolean.TRUE.equals(n.isDefault())?repository.activeDefaultsExcept(t[0],t[1],owner,id):List.of();if(repository.updateContact(t[0],t[1],owner,id,v,n,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.contact(t[0],t[1],owner,id).isPresent());auditDemotions(a,type,owner,rid,demoted,t);ContactResponse after=contact(type,owner,id);write(a,"UPDATE",type+"_CONTACT",id,rid,contactSummary(before),contactSummary(after));return after;}
    @Transactional public ContactResponse archiveContact(PartyType type,UUID owner,UUID id,long v,ErpPrincipal a,String rid){ContactResponse before=contact(type,owner,id);String[] t=contactTable(type);repository.lockOwner(type==PartyType.CUSTOMER?"customer":"supplier",owner);if(repository.archiveContact(t[0],t[1],owner,id,v,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.contact(t[0],t[1],owner,id).isPresent());ContactResponse after=contact(type,owner,id);write(a,"ARCHIVE",type+"_CONTACT",id,rid,contactSummary(before),contactSummary(after));return after;}
    private void auditDemotions(ErpPrincipal actor,PartyType type,UUID owner,String requestId,List<ContactResponse> before,String[] table){for(ContactResponse old:before){ContactResponse after=repository.contact(table[0],table[1],owner,old.id()).orElseThrow();write(actor,"UPDATE",type+"_CONTACT",old.id(),requestId,contactSummary(old),contactSummary(after));}}
    private void ensureOwner(PartyType t,UUID id){if(t==PartyType.CUSTOMER)customer(id);else supplier(id);}
    private static String[] contactTable(PartyType t){return t==PartyType.CUSTOMER?new String[]{"customer_contact","customer_id"}:new String[]{"supplier_contact","supplier_id"};}
    private static String text(String v){return MasterDataSupport.requiredText(v);} private static String opt(String v){return MasterDataSupport.optionalText(v);}
    private static void duplicate(){throw new ApiException(ApiErrorCode.DUPLICATE_BUSINESS_KEY,"A resource with the same business key already exists.");}
    private void write(ErpPrincipal a,String action,String type,UUID id,String rid,Map<String,Object> before,Map<String,Object> after){audit.write(a.user().id(),action,type,id,rid,null,before,after);}
    private static Map<String,Object> summary(CustomerResponse v){Map<String,Object> data=base(v.id(),v.version(),v.status());put(data,"shortName",v.shortName());put(data,"name",v.name());put(data,"address",v.address());put(data,"telephone",v.telephone());put(data,"currencyCode",v.currencyCode());return data;}
    private static Map<String,Object> summary(SupplierResponse v){Map<String,Object> data=base(v.id(),v.version(),v.status());put(data,"name",v.name());put(data,"address",v.address());put(data,"telephone",v.telephone());return data;}
    private static Map<String,Object> contactSummary(ContactResponse v){Map<String,Object> data=base(v.id(),v.version(),v.status());put(data,"division",v.division());put(data,"name",v.name());put(data,"telephone",v.telephone());put(data,"email",v.email());put(data,"remark",v.remark());put(data,"isDefault",v.isDefault());return data;}
    private static Map<String,Object> base(UUID id,long version,String status){Map<String,Object> data=new java.util.LinkedHashMap<>();data.put("id",id);data.put("version",version);data.put("status",status);return data;}
    private static void put(Map<String,Object> data,String key,Object value){data.put(key,value);}
}
