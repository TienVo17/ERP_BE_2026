package com.company.erp.masterdata.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.application.AdminQuery;
import com.company.erp.masterdata.api.MasterDataModels.ProcessCreateRequest;
import com.company.erp.masterdata.api.MasterDataModels.ProcessResponse;
import com.company.erp.masterdata.api.MasterDataModels.ProcessUpdateRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessMasterJdbcRepository {
    private static final Map<String,String> SORT=Map.of("sequenceNo","sequence_no","name","lower(name)","id","id");
    private final JdbcClient jdbc;
    public ProcessMasterJdbcRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public List<String> sort(List<String> requested){return AdminQuery.sort(requested,SORT,List.of("sequenceNo,asc","id,asc"));}
    public List<ProcessResponse> list(int page,int size,String status,String name,List<String> sort){return jdbc.sql("SELECT * FROM master_data.process_master WHERE (CAST(:status AS varchar) IS NULL OR status=:status) AND (CAST(:name AS varchar) IS NULL OR lower(name) LIKE lower(:name)) ORDER BY "+AdminQuery.orderBy(sort,SORT)+" LIMIT :limit OFFSET :offset").param("status",status).param("name",name==null?null:"%"+name+"%").param("limit",size).param("offset",Math.multiplyExact((long)page,size)).query((r,n)->map(r)).list();}
    public long count(String status,String name){return jdbc.sql("SELECT count(*) FROM master_data.process_master WHERE (CAST(:status AS varchar) IS NULL OR status=:status) AND (CAST(:name AS varchar) IS NULL OR lower(name) LIKE lower(:name))").param("status",status).param("name",name==null?null:"%"+name+"%").query(Long.class).single();}
    public Optional<ProcessResponse> find(UUID id){return jdbc.sql("SELECT * FROM master_data.process_master WHERE id=:id").param("id",id).query((r,n)->map(r)).optional();}
    public boolean keyExists(String name,String qr,UUID excluded){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM master_data.process_master WHERE (upper(btrim(name))=:name OR qr_value=:qr) AND (CAST(:excluded AS uuid) IS NULL OR id<>:excluded))").param("name",name.toUpperCase()).param("qr",qr).param("excluded",excluded).query(Boolean.class).single();}
    public UUID create(ProcessCreateRequest q,UUID a){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO master_data.process_master(id,name,sequence_no,qr_value,created_by,updated_by) VALUES(:id,:name,:sequence,:qr,:a,:a)").param("id",id).param("name",q.name()).param("sequence",q.sequenceNo()).param("qr",q.qrValue()).param("a",a).update();return id;}
    public int update(UUID id,long v,ProcessUpdateRequest q,UUID a){return jdbc.sql("UPDATE master_data.process_master SET name=:name,sequence_no=:sequence,version=version+1,updated_by=:a WHERE id=:id AND version=:v").param("name",q.name()).param("sequence",q.sequenceNo()).param("a",a).param("id",id).param("v",v).update();}
    public int archive(UUID id,long v,UUID a){return jdbc.sql("UPDATE master_data.process_master SET status='ARCHIVED',version=version+1,updated_by=:a WHERE id=:id AND version=:v").param("a",a).param("id",id).param("v",v).update();}
    public boolean used(UUID id){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM production.production_order_process WHERE process_id=:id)").param("id",id).query(Boolean.class).single();}
    private static ProcessResponse map(ResultSet r)throws SQLException{return new ProcessResponse(r.getObject("id",UUID.class),r.getLong("version"),r.getString("status"),r.getString("name"),r.getInt("sequence_no"),r.getString("qr_value"),r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("updated_at",OffsetDateTime.class).toInstant());}
}
