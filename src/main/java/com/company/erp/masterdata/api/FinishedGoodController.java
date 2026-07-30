package com.company.erp.masterdata.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.masterdata.api.MasterDataModels.FinishedGoodRequest;
import com.company.erp.masterdata.api.MasterDataModels.FinishedGoodResponse;
import com.company.erp.masterdata.api.MasterDataModels.PageResponse;
import com.company.erp.masterdata.application.FinishedGoodService;
import com.company.erp.masterdata.application.MasterDataSupport;
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

@RestController @RequestMapping("/api/v1/master-data/finished-goods") @Validated
public class FinishedGoodController {
    private final FinishedGoodService service; public FinishedGoodController(FinishedGoodService service){this.service=service;}
    @GetMapping @PreAuthorize("hasAuthority('FINISHED_GOODS:VIEW')") PageResponse<FinishedGoodResponse> list(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="25")@Min(1)@Max(100)int size,@RequestParam(required=false)List<String> sort,@RequestParam(required=false)String status,@RequestParam(required=false)String productKind,@RequestParam(required=false)String styleNo,@RequestParam(required=false)String name){return service.list(page,size,sort,status,productKind,styleNo,name);}
    @PostMapping @PreAuthorize("hasAuthority('FINISHED_GOODS:CREATE')") ResponseEntity<FinishedGoodResponse> create(@Valid@RequestBody FinishedGoodRequest q,JwtAuthenticationToken a,HttpServletRequest r){return ResponseEntity.status(201).body(service.create(q,MasterDataSupport.principal(a),MasterDataSupport.requestId(r)));}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('FINISHED_GOODS:VIEW')") FinishedGoodResponse get(@PathVariable UUID id){return service.get(id);}
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('FINISHED_GOODS:UPDATE')") FinishedGoodResponse update(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,@Valid@RequestBody FinishedGoodRequest q,JwtAuthenticationToken a,HttpServletRequest r){return service.update(id,MasterDataSupport.version(m),q,MasterDataSupport.principal(a),MasterDataSupport.requestId(r));}
    @PostMapping("/{id}/archive") @PreAuthorize("hasAuthority('FINISHED_GOODS:ARCHIVE')") FinishedGoodResponse archive(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,JwtAuthenticationToken a,HttpServletRequest r){return service.archive(id,MasterDataSupport.version(m),MasterDataSupport.principal(a),MasterDataSupport.requestId(r));}
}
