package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.CareerMap;
import com.careernav.domain.ApiModels.CareerRoleJobs;
import com.careernav.service.CareerMapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/career-map")
public class CareerMapController {
    private final CareerMapService service;
    public CareerMapController(CareerMapService service) { this.service = service; }

    @GetMapping
    public ApiResponse<CareerMap> get() { return ApiResponse.ok(service.get()); }


    @GetMapping("/roles/{roleId}/jobs")
    public ApiResponse<CareerRoleJobs> roleJobs(@PathVariable String roleId,
                                                  @RequestParam(defaultValue="") String city,
                                                  @RequestParam(defaultValue="0") int page,
                                                  @RequestParam(defaultValue="24") int size) {
        return ApiResponse.ok(service.jobsForRole(roleId, city, page, size));
    }
}
