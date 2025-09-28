package com.smartdocumentchat.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@Slf4j
public class WebController {

    /**
     * דף התחברות
     */
    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "msg", required = false) String msg,
                            Model model) {

        if (error != null) {
            if ("invalid".equals(error)) {
                model.addAttribute("errorMsg", "שם משתמש או סיסמה שגויים");
            } else if ("expired".equals(error)) {
                model.addAttribute("errorMsg", "הפגישה פגה תוקף, אנא התחבר שוב");
            } else {
                model.addAttribute("errorMsg", "שגיאה בהתחברות");
            }
        }

        if (msg != null) {
            model.addAttribute("msg", msg);
        }

        log.debug("Displaying login page");
        return "login";
    }

    /**
     * דף רישום
     */
    @GetMapping("/register")
    public String registerPage(@RequestParam(value = "error", required = false) String error,
                               @RequestParam(value = "msg", required = false) String msg,
                               Model model) {

        if (error != null) {
            model.addAttribute("errorMsg", "שגיאה ברישום: " + error);
        }

        if (msg != null) {
            model.addAttribute("msg", msg);
        }

        log.debug("Displaying register page");
        return "register";
    }

    /**
     * דף הבית - לאחר התחברות מוצלחת
     */
    @GetMapping("/")
    public String homePage(Model model) {
        log.debug("Displaying home page");
        return "home";
    }

    /**
     * דף שגיאה כללי
     */
    @GetMapping("/error")
    public String errorPage(Model model) {
        log.debug("Displaying error page");
        return "error";
    }
}