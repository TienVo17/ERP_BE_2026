package com.company.erp.masterdata.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.masterdata.api.MasterDataModels.PageResponse;
import com.company.erp.masterdata.api.MasterDataModels.RawMaterialRequest;
import com.company.erp.masterdata.api.MasterDataModels.RawMaterialResponse;
import com.company.erp.masterdata.application.MasterDataSupport;
import com.company.erp.masterdata.application.RawMaterialService;
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

@RestController @RequestMapping("/api/v1/master-data/raw-materials") @Validated
public class RawMaterialController {
    private final RawMaterialService service; public RawMaterialController(RawMaterialService service){this.service=service;}
    @GetMapping @PreAuthorize("hasAuthority('RAW_MATERIAL:VIEW')") PageResponse<RawMaterialResponse> list(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="25")@Min(1)@Max(100)int size,@RequestParam(required=false)List<String> sort,@RequestParam(required=false)String status,@RequestParam(required=false)String code,@RequestParam(required=false)String name,@RequestParam(required=false)UUID supplierId){return service.list(page,size,sort,status,code,name,supplierId);}
    @PostMapping @PreAuthorize("hasAuthority('RAW_MATERIAL:CREATE')") ResponseEntity<RawMaterialResponse> create(@Valid@RequestBody RawMaterialRequest q,JwtAuthenticationToken a,HttpServletRequest r){return ResponseEntity.status(201).body(service.create(q,MasterDataSupport.principal(a),MasterDataSupport.requestId(r)));}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('RAW_MATERIAL:VIEW')") RawMaterialResponse get(@PathVariable UUID id){return service.get(id);}
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('RAW_MATERIAL:UPDATE')") RawMaterialResponse update(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,@Valid@RequestBody RawMaterialRequest q,JwtAuthenticationToken a,HttpServletRequest r){return service.update(id,MasterDataSupport.version(m),q,MasterDataSupport.principal(a),MasterDataSupport.requestId(r));}
    @PostMapping("/{id}/archive") @PreAuthorize("hasAuthority('RAW_MATERIAL:ARCHIVE')") RawMaterialResponse archive(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,JwtAuthenticationToken a,HttpServletRequest r){return service.archive(id,MasterDataSupport.version(m),MasterDataSupport.principal(a),MasterDataSupport.requestId(r));}
}
