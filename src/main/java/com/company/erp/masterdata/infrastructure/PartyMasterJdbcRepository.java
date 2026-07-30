package com.company.erp.masterdata.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.application.AdminQuery;
import com.company.erp.masterdata.api.MasterDataModels.*;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PartyMasterJdbcRepository {
    private static final Map<String,String> CUSTOMER_SORT=Map.of("name","lower(name)","shortName","upper(short_name)","id","id");
    private static final Map<String,String> SUPPLIER_SORT=Map.of("name","lower(name)","id","id");
    private static final Map<String,String> CONTACT_SORT=Map.of("name","lower(name)","id","id");
    private final JdbcClient jdbc;
    public PartyMasterJdbcRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    public List<String> customerSort(List<String> s){return AdminQuery.sort(s,CUSTOMER_SORT,List.of("name,asc","id,asc"));}
    public List<String> supplierSort(List<String> s){return AdminQuery.sort(s,SUPPLIER_SORT,List.of("name,asc","id,asc"));}
    public List<String> contactSort(List<String> s){return AdminQuery.sort(s,CONTACT_SORT,List.of("name,asc","id,asc"));}

    public Optional<CustomerResponse> customer(UUID id){return jdbc.sql("SELECT * FROM master_data.customer WHERE id=:id").param("id",id).query((r,n)->customer(r,contacts("customer_contact","customer_id",id))).optional();}
    public List<CustomerResponse> customers(int page,int size,String status,String name,String shortName,List<String> sort){
        List<CustomerResponse> rows=jdbc.sql("SELECT * FROM master_data.customer WHERE (CAST(:status AS varchar) IS NULL OR status=:status) AND (CAST(:name AS varchar) IS NULL OR lower(name) LIKE lower(:name)) AND (CAST(:shortName AS varchar) IS NULL OR upper(short_name)=:shortName) ORDER BY "+AdminQuery.orderBy(sort,CUSTOMER_SORT)+" LIMIT :limit OFFSET :offset").param("status",status).param("name",name==null?null:"%"+name+"%").param("shortName",shortName).param("limit",size).param("offset",Math.multiplyExact((long)page,size)).query((r,n)->customer(r,List.of())).list();
        Map<UUID,List<ContactResponse>> contactsByOwner=contactsForOwners("customer_contact","customer_id",rows.stream().map(CustomerResponse::id).toList());
        return rows.stream().map(c->new CustomerResponse(c.id(),c.version(),c.status(),c.shortName(),c.name(),c.address(),c.telephone(),c.currencyCode(),contactsByOwner.getOrDefault(c.id(),List.of()),c.createdAt(),c.updatedAt())).toList();
    }
    public long countCustomers(String status,String name,String shortName){return jdbc.sql("SELECT count(*) FROM master_data.customer WHERE (CAST(:status AS varchar) IS NULL OR status=:status) AND (CAST(:name AS varchar) IS NULL OR lower(name) LIKE lower(:name)) AND (CAST(:shortName AS varchar) IS NULL OR upper(short_name)=:shortName)").param("status",status).param("name",name==null?null:"%"+name+"%").param("shortName",shortName).query(Long.class).single();}
    public boolean customerKeyExists(String key){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM master_data.customer WHERE upper(btrim(short_name))=:key)").param("key",key).query(Boolean.class).single();}
    public UUID createCustomer(CustomerCreateRequest q,UUID a){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO master_data.customer(id,short_name,name,address,telephone,currency_code,created_by,updated_by) VALUES(:id,:shortName,:name,:address,:telephone,:currency,:a,:a)").param("id",id).param("shortName",q.shortName()).param("name",q.name()).param("address",q.address()).param("telephone",q.telephone()).param("currency",q.currencyCode()).param("a",a).update();return id;}
    public int updateCustomer(UUID id,long v,CustomerUpdateRequest q,UUID a){return jdbc.sql("UPDATE master_data.customer SET name=:name,address=:address,telephone=:telephone,currency_code=:currency,version=version+1,updated_by=:a WHERE id=:id AND version=:v").param("name",q.name()).param("address",q.address()).param("telephone",q.telephone()).param("currency",q.currencyCode()).param("a",a).param("id",id).param("v",v).update();}
    public int archiveCustomer(UUID id,long v,UUID a){return archive("customer",id,v,a);}
    public boolean customerUsed(UUID id){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sales.buyer_order WHERE customer_id=:id UNION ALL SELECT 1 FROM inventory.stock_position WHERE customer_id=:id UNION ALL SELECT 1 FROM delivery.delivery_note WHERE customer_id=:id)").param("id",id).query(Boolean.class).single();}

    public Optional<SupplierResponse> supplier(UUID id){return jdbc.sql("SELECT * FROM master_data.supplier WHERE id=:id").param("id",id).query((r,n)->supplier(r,contacts("supplier_contact","supplier_id",id))).optional();}
    public List<SupplierResponse> suppliers(int page,int size,String status,String name,List<String> sort){List<SupplierResponse> rows=jdbc.sql("SELECT * FROM master_data.supplier WHERE (CAST(:status AS varchar) IS NULL OR status=:status) AND (CAST(:name AS varchar) IS NULL OR lower(name) LIKE lower(:name)) ORDER BY "+AdminQuery.orderBy(sort,SUPPLIER_SORT)+" LIMIT :limit OFFSET :offset").param("status",status).param("name",name==null?null:"%"+name+"%").param("limit",size).param("offset",Math.multiplyExact((long)page,size)).query((r,n)->supplier(r,List.of())).list();Map<UUID,List<ContactResponse>> contactsByOwner=contactsForOwners("supplier_contact","supplier_id",rows.stream().map(SupplierResponse::id).toList());return rows.stream().map(s->new SupplierResponse(s.id(),s.version(),s.status(),s.name(),s.address(),s.telephone(),contactsByOwner.getOrDefault(s.id(),List.of()),s.createdAt(),s.updatedAt())).toList();}
    public long countSuppliers(String status,String name){return jdbc.sql("SELECT count(*) FROM master_data.supplier WHERE (CAST(:status AS varchar) IS NULL OR status=:status) AND (CAST(:name AS varchar) IS NULL OR lower(name) LIKE lower(:name))").param("status",status).param("name",name==null?null:"%"+name+"%").query(Long.class).single();}
    public boolean supplierNameExists(String name,UUID excluded){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM master_data.supplier WHERE upper(btrim(name))=:name AND (CAST(:excluded AS uuid) IS NULL OR id<>:excluded))").param("name",name.toUpperCase()).param("excluded",excluded).query(Boolean.class).single();}
    public UUID createSupplier(SupplierRequest q,UUID a){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO master_data.supplier(id,name,address,telephone,created_by,updated_by) VALUES(:id,:name,:address,:telephone,:a,:a)").param("id",id).param("name",q.name()).param("address",q.address()).param("telephone",q.telephone()).param("a",a).update();return id;}
    public int updateSupplier(UUID id,long v,SupplierRequest q,UUID a){return jdbc.sql("UPDATE master_data.supplier SET name=:name,address=:address,telephone=:telephone,version=version+1,updated_by=:a WHERE id=:id AND version=:v").param("name",q.name()).param("address",q.address()).param("telephone",q.telephone()).param("a",a).param("id",id).param("v",v).update();}
    public int archiveSupplier(UUID id,long v,UUID a){return archive("supplier",id,v,a);}
    public boolean supplierUsed(UUID id){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM master_data.raw_material WHERE supplier_id=:id)").param("id",id).query(Boolean.class).single();}

    public void lockOwner(String table,UUID id){jdbc.sql("SELECT id FROM master_data."+table+" WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional();}
    public List<ContactResponse> contacts(String table,String ownerColumn,UUID owner){return jdbc.sql("SELECT * FROM master_data."+table+" WHERE "+ownerColumn+"=:owner ORDER BY is_default DESC, lower(name),id").param("owner",owner).query((r,n)->contact(r)).list();}
    private Map<UUID,List<ContactResponse>> contactsForOwners(String table,String ownerColumn,List<UUID> owners){
        if(owners.isEmpty())return Map.of();
        Map<UUID,List<ContactResponse>> grouped=new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT * FROM master_data."+table+" WHERE "+ownerColumn+" IN (:owners) ORDER BY "+ownerColumn+",is_default DESC,lower(name),id").param("owners",owners).query((r,n)->new OwnerContact(r.getObject(ownerColumn,UUID.class),contact(r))).list().forEach(row->grouped.computeIfAbsent(row.ownerId(),ignored->new java.util.ArrayList<>()).add(row.contact()));
        return grouped;
    }
    private record OwnerContact(UUID ownerId,ContactResponse contact){}
    public long countContacts(String table,String ownerColumn,UUID owner,String status){return jdbc.sql("SELECT count(*) FROM master_data."+table+" WHERE "+ownerColumn+"=:owner AND (CAST(:status AS varchar) IS NULL OR status=:status)").param("owner",owner).param("status",status).query(Long.class).single();}
    public List<ContactResponse> listContacts(String table,String ownerColumn,UUID owner,int page,int size,String status,List<String> sort){return jdbc.sql("SELECT * FROM master_data."+table+" WHERE "+ownerColumn+"=:owner AND (CAST(:status AS varchar) IS NULL OR status=:status) ORDER BY "+AdminQuery.orderBy(sort,CONTACT_SORT)+" LIMIT :limit OFFSET :offset").param("owner",owner).param("status",status).param("limit",size).param("offset",Math.multiplyExact((long)page,size)).query((r,n)->contact(r)).list();}
    public Optional<ContactResponse> contact(String table,String ownerColumn,UUID owner,UUID id){return jdbc.sql("SELECT * FROM master_data."+table+" WHERE id=:id AND "+ownerColumn+"=:owner").param("id",id).param("owner",owner).query((r,n)->contact(r)).optional();}
    public UUID createContact(String table,String ownerColumn,UUID owner,ContactCreateRequest q,UUID a){if(Boolean.TRUE.equals(q.isDefault()))clearDefaults(table,ownerColumn,owner,null,a);UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO master_data."+table+"(id,"+ownerColumn+",division,name,telephone,email,remark,is_default,created_by,updated_by) VALUES(:id,:owner,:division,:name,:telephone,:email,:remark,:default,:a,:a)").param("id",id).param("owner",owner).param("division",q.division()).param("name",q.name()).param("telephone",q.telephone()).param("email",q.email()).param("remark",q.remark()).param("default",Boolean.TRUE.equals(q.isDefault())).param("a",a).update();return id;}
    public int updateContact(String table,String ownerColumn,UUID owner,UUID id,long v,ContactUpdateRequest q,UUID a){if(Boolean.TRUE.equals(q.isDefault()))clearDefaults(table,ownerColumn,owner,id,a);return jdbc.sql("UPDATE master_data."+table+" SET division=:division,name=:name,telephone=:telephone,email=:email,remark=:remark,is_default=CASE WHEN CAST(:default AS boolean) IS TRUE THEN true ELSE is_default END,version=version+1,updated_by=:a WHERE id=:id AND "+ownerColumn+"=:owner AND version=:v").param("division",q.division()).param("name",q.name()).param("telephone",q.telephone()).param("email",q.email()).param("remark",q.remark()).param("default",q.isDefault()).param("a",a).param("id",id).param("owner",owner).param("v",v).update();}
    public int archiveContact(String table,String ownerColumn,UUID owner,UUID id,long v,UUID a){return jdbc.sql("UPDATE master_data."+table+" SET status='ARCHIVED',is_default=false,version=version+1,updated_by=:a WHERE id=:id AND "+ownerColumn+"=:owner AND version=:v").param("a",a).param("id",id).param("owner",owner).param("v",v).update();}
    public List<ContactResponse> activeDefaultsExcept(String table,String ownerColumn,UUID owner,UUID excluded){return jdbc.sql("SELECT * FROM master_data."+table+" WHERE "+ownerColumn+"=:owner AND is_default AND status='ACTIVE' AND (CAST(:excluded AS uuid) IS NULL OR id<>:excluded)").param("owner",owner).param("excluded",excluded).query((r,n)->contact(r)).list();}
    private void clearDefaults(String table,String ownerColumn,UUID owner,UUID excluded,UUID a){jdbc.sql("UPDATE master_data."+table+" SET is_default=false,version=version+1,updated_by=:a WHERE "+ownerColumn+"=:owner AND is_default AND status='ACTIVE' AND (CAST(:excluded AS uuid) IS NULL OR id<>:excluded)").param("a",a).param("owner",owner).param("excluded",excluded).update();}
    private int archive(String table,UUID id,long v,UUID a){return jdbc.sql("UPDATE master_data."+table+" SET status='ARCHIVED',version=version+1,updated_by=:a WHERE id=:id AND version=:v").param("a",a).param("id",id).param("v",v).update();}
    private static CustomerResponse customer(ResultSet r,List<ContactResponse> c)throws SQLException{return new CustomerResponse(r.getObject("id",UUID.class),r.getLong("version"),r.getString("status"),r.getString("short_name"),r.getString("name"),r.getString("address"),r.getString("telephone"),r.getString("currency_code"),c,time(r,"created_at"),time(r,"updated_at"));}
    private static SupplierResponse supplier(ResultSet r,List<ContactResponse> c)throws SQLException{return new SupplierResponse(r.getObject("id",UUID.class),r.getLong("version"),r.getString("status"),r.getString("name"),r.getString("address"),r.getString("telephone"),c,time(r,"created_at"),time(r,"updated_at"));}
    private static ContactResponse contact(ResultSet r)throws SQLException{return new ContactResponse(r.getObject("id",UUID.class),r.getLong("version"),r.getString("status"),r.getString("division"),r.getString("name"),r.getString("telephone"),r.getString("email"),r.getString("remark"),r.getBoolean("is_default"),time(r,"created_at"),time(r,"updated_at"));}
    private static java.time.Instant time(ResultSet r,String c)throws SQLException{return r.getObject(c,OffsetDateTime.class).toInstant();}
}
