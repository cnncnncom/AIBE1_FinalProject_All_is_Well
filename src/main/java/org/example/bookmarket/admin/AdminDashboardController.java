package org.example.bookmarket.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminDashboardController {

    @GetMapping("/admin")
    public String dashboard() {
        return "admin/dashboard";
    }
}