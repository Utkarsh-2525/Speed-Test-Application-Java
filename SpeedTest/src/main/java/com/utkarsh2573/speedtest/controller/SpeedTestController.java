package com.utkarsh2573.speedtest.controller;

import com.utkarsh2573.speedtest.model.SpeedTestResult;
import com.utkarsh2573.speedtest.service.SpeedTestService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpeedTestController {

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/start")
    public String startTest(Model model) {
        SpeedTestService tester = new SpeedTestService();
        try {
            SpeedTestResult result = tester.runTest();
            model.addAttribute("result", result);
        } catch (Exception e) {
            model.addAttribute("error", "Test failed: " + e.getMessage());
        }
        return "index";
    }
}