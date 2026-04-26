package org.tanzu.goosechat;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards GET {@code /login} to the SPA's {@code index.html} so the Angular app can
 * render the login form. Spring Security's form-login filter handles POST {@code /login}
 * before this controller is reached, so submissions are unaffected.
 *
 * <p>Harmless in OAuth2 mode: {@code /login} is not permitted there, so unauthenticated
 * requests trigger the OAuth2 redirect entry point and never reach this handler.
 */
@Controller
public class LoginPageController {

    @GetMapping("/login")
    public String login() {
        return "forward:/index.html";
    }
}
