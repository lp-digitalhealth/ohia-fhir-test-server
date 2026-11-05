package org.ohia.facade.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the static viewer at /viewer/ (Spring Boot only serves a folder's index at the site root). */
@Controller
public class ViewerController {

    @GetMapping({"/viewer", "/viewer/"})
    public String viewer() {
        return "forward:/viewer/index.html";
    }
}
