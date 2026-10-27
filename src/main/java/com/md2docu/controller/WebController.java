package com.md2docu.controller;

import com.md2docu.util.PageLayout;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("minBodyWidthMm", PageLayout.MIN_BODY_WIDTH_MM);
        model.addAttribute("minBodyHeightMm", PageLayout.MIN_BODY_HEIGHT_MM);
        return "index";
    }
}
