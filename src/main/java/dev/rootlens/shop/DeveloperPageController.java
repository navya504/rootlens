package dev.rootlens.shop;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DeveloperPageController {
    @GetMapping("/dev")
    public String developerConsole() { return "forward:/developer.html"; }

    @GetMapping("/view")
    public String indexExplorer() { return "forward:/view.html"; }

    @GetMapping("/visual")
    public String ragIndexVisual() { return "forward:/visual.html"; }
}
